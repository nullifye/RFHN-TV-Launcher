#include <jni.h>
#include <stdatomic.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <time.h>
#include <string.h>
#include <strings.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include "zdtun.h"

typedef struct {
    JNIEnv *env;
    jobject service;
    jmethodID protect, opened, traffic, closed, info;
    int fd, failed;
} monitor_t;

typedef struct { jlong id, sent, received; int looked; } counter_t;

/* ---- What a connection's first outgoing bytes say, without keeping them ----
 * TLS: the server name (SNI) and the protocol the app asks for (ALPN), both sent in plain text
 * before encryption starts. HTTP: the request line and Host. DNS: the name looked up. Nothing
 * else is read, and nothing is stored here. */

/* Printable ASCII only, at most cap-1 chars: safe for NewStringUTF and the screen. */
static void copy_text(char *out, size_t cap, const unsigned char *p, size_t n) {
    size_t j = 0;
    for (size_t i = 0; i < n && j + 1 < cap; i++) out[j++] = (p[i] >= 0x20 && p[i] < 0x7f) ? (char)p[i] : '?';
    out[j] = 0;
}

static unsigned rd16(const unsigned char *p) { return (unsigned)p[0] << 8 | p[1]; }

/* A TLS ClientHello: its server name and first ALPN protocol. 1 if it was one. */
static int tls_hello(const unsigned char *p, size_t n, char *host, size_t hcap, char *alpn, size_t acap) {
    if (n < 43 || p[0] != 0x16 || p[1] != 0x03 || p[5] != 0x01) return 0;
    size_t i = 5 + 4 + 2 + 32;                      /* record, handshake header, version, random */
    if (i >= n) return 1;
    i += 1 + p[i];                                  /* session id */
    if (i + 2 > n) return 1;
    i += 2 + rd16(p + i);                           /* cipher suites */
    if (i + 1 > n) return 1;
    i += 1 + p[i];                                  /* compression */
    if (i + 2 > n) return 1;
    size_t end = i + 2 + rd16(p + i);
    if (end > n) end = n;                           /* the rest may be in the next packet */
    i += 2;
    while (i + 4 <= end) {
        unsigned type = rd16(p + i), len = rd16(p + i + 2);
        const unsigned char *e = p + i + 4;
        if (i + 4 + len > end) break;
        if (type == 0x0000 && len >= 5 && e[2] == 0) { /* server_name: list, type host_name, name */
            unsigned nl = rd16(e + 3);
            if (5 + nl <= len) copy_text(host, hcap, e + 5, nl);
        } else if (type == 0x0010 && len >= 3) {    /* ALPN: list, then length-prefixed names */
            unsigned pl = e[2];
            if (3 + pl <= len) copy_text(alpn, acap, e + 3, pl);
        }
        i += 4 + len;
    }
    return 1;
}

/* An HTTP request: "GET /path" and the Host header. 1 if it was one. */
static int http_request(const unsigned char *p, size_t n, char *host, size_t hcap, char *line, size_t lcap) {
    static const char *methods[] = {"GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT "};
    int ok = 0;
    for (size_t m = 0; m < sizeof(methods) / sizeof(*methods); m++) {
        size_t ml = strlen(methods[m]);
        if (n > ml && memcmp(p, methods[m], ml) == 0) { ok = 1; break; }
    }
    if (!ok) return 0;
    size_t eol = 0;
    while (eol < n && p[eol] != '\r' && p[eol] != '\n') eol++;
    /* "GET /path HTTP/1.1": drop the version */
    size_t cut = eol;
    for (size_t k = eol; k > 0; k--) if (p[k - 1] == ' ') { cut = k - 1; break; }
    copy_text(line, lcap, p, cut);
    for (size_t k = eol; k + 6 < n; k++) {
        if ((p[k] == '\n') && (p[k + 1] == 'H' || p[k + 1] == 'h') && strncasecmp((const char *)p + k + 1, "host:", 5) == 0) {
            size_t a = k + 6;
            while (a < n && p[a] == ' ') a++;
            size_t b = a;
            while (b < n && p[b] != '\r' && p[b] != '\n') b++;
            copy_text(host, hcap, p + a, b - a);
            break;
        }
    }
    return 1;
}

/* A DNS query: the name asked for. 1 if it was one. */
static int dns_query(const unsigned char *p, size_t n, char *name, size_t cap) {
    if (n < 17 || rd16(p + 4) == 0 || (p[2] & 0x80)) return 0; /* a question, not an answer */
    size_t i = 12, j = 0;
    while (i < n && p[i] != 0) {
        unsigned l = p[i++];
        if (l > 63 || i + l > n) return 0;
        if (j && j + 1 < cap) name[j++] = '.';
        for (unsigned k = 0; k < l && j + 1 < cap; k++, i++) name[j++] = (p[i] >= 0x20 && p[i] < 0x7f) ? (char)p[i] : '?';
    }
    name[j] = 0;
    return j > 0;
}

static jstring jstr(JNIEnv *e, const char *s) { return s && *s ? (*e)->NewStringUTF(e, s) : NULL; }
static atomic_bool stopping = ATOMIC_VAR_INIT(0);

static int send_client(zdtun_t *tun, zdtun_pkt_t *pkt, const zdtun_conn_t *conn) {
    monitor_t *m = zdtun_userdata(tun);
    // A nonblocking TUN may temporarily fill up: TCP will retransmit, UDP remains best effort.
    ssize_t n = write(m->fd, pkt->buf, pkt->len);
    return n == pkt->len ? 0 : -1;
}

static void protect_socket(zdtun_t *tun, socket_t fd) {
    monitor_t *m = zdtun_userdata(tun);
    if (fd >= FD_SETSIZE || !(*m->env)->CallBooleanMethod(m->env, m->service, m->protect, fd)) {
        m->failed = 1;
        shutdown(fd, SHUT_RDWR);
    }
}

static int opened(zdtun_t *tun, zdtun_conn_t *conn) {
    monitor_t *m = zdtun_userdata(tun);
    const zdtun_5tuple_t *t = zdtun_conn_get_5tuple(conn);
    char src[INET6_ADDRSTRLEN], dst[INET6_ADDRSTRLEN];
    int family = t->ipver == 4 ? AF_INET : AF_INET6;
    inet_ntop(family, &t->src_ip, src, sizeof(src));
    inet_ntop(family, &t->dst_ip, dst, sizeof(dst));
    JNIEnv *e = m->env;
    jstring source = (*e)->NewStringUTF(e, src), target = (*e)->NewStringUTF(e, dst);
    jlong id = (*e)->CallLongMethod(e, m->service, m->opened, source, target,
            (jint)ntohs(t->src_port), (jint)ntohs(t->dst_port), (jint)t->ipproto);
    (*e)->DeleteLocalRef(e, source);
    (*e)->DeleteLocalRef(e, target);
    counter_t *c = calloc(1, sizeof(counter_t));
    if (!c) { m->failed = 1; return 1; }
    c->id = id;
    zdtun_conn_set_userdata(conn, c);
    return 0;
}

static void account(zdtun_t *tun, const zdtun_pkt_t *pkt, uint8_t outbound, const zdtun_conn_t *conn) {
    counter_t *c = zdtun_conn_get_userdata(conn);
    if (!c) return;
    if (outbound) c->sent += pkt->len; else c->received += pkt->len;
    /* The first outgoing data packet (or two, if the TLS hello is split) says what it is. */
    if (!outbound || c->looked >= 2 || pkt->l7_len == 0 || !pkt->l7) return;
    c->looked++;
    const unsigned char *p = (const unsigned char *)pkt->l7;
    size_t n = pkt->l7_len;
    char host[256] = "", detail[256] = "";
    const char *kind = NULL;
    int proto = pkt->tuple.ipproto, port = ntohs(pkt->tuple.dst_port);
    if (proto == IPPROTO_TCP && tls_hello(p, n, host, sizeof(host), detail, sizeof(detail))) {
        kind = port == 853 ? "DNS over TLS" : "TLS";
    } else if (proto == IPPROTO_TCP && http_request(p, n, host, sizeof(host), detail, sizeof(detail))) {
        kind = "HTTP";
    } else if (proto == IPPROTO_UDP && port == 53 && dns_query(p, n, detail, sizeof(detail))) {
        kind = "DNS";
    } else if (proto == IPPROTO_UDP && port == 443 && (p[0] & 0x80)) {
        kind = "QUIC";
    } else if (c->looked == 1) {
        return; /* not recognised yet: the next packet may be */
    } else {
        return;
    }
    c->looked = 2; /* done with this connection */
    monitor_t *m = zdtun_userdata(tun);
    JNIEnv *e = m->env;
    jstring h = jstr(e, host), d = jstr(e, detail), k = jstr(e, kind);
    (*e)->CallVoidMethod(e, m->service, m->info, c->id, h, d, k);
    if (h) (*e)->DeleteLocalRef(e, h);
    if (d) (*e)->DeleteLocalRef(e, d);
    if (k) (*e)->DeleteLocalRef(e, k);
}

static int flush(zdtun_t *tun, const zdtun_conn_t *conn, void *unused) {
    monitor_t *m = zdtun_userdata(tun);
    counter_t *c = zdtun_conn_get_userdata(conn);
    if (c && (c->sent || c->received)) {
        (*m->env)->CallVoidMethod(m->env, m->service, m->traffic, c->id, c->sent, c->received);
        c->sent = c->received = 0;
    }
    return 0;
}

static void closed(zdtun_t *tun, const zdtun_conn_t *conn) {
    monitor_t *m = zdtun_userdata(tun);
    counter_t *c = zdtun_conn_get_userdata(conn);
    flush(tun, conn, NULL);
    if (c) (*m->env)->CallVoidMethod(m->env, m->service, m->closed, c->id);
    free(zdtun_conn_get_userdata(conn));
    zdtun_conn_set_userdata((zdtun_conn_t *)conn, NULL);
}

JNIEXPORT void JNICALL
Java_my_rafhan_app_tvhome_TrafficVpnService_nativeReset(JNIEnv *e, jclass cls) {
    atomic_store(&stopping, 0);
}

JNIEXPORT void JNICALL
Java_my_rafhan_app_tvhome_TrafficVpnService_nativeStop(JNIEnv *e, jclass cls) {
    atomic_store(&stopping, 1);
}

JNIEXPORT jint JNICALL
Java_my_rafhan_app_tvhome_TrafficVpnService_nativeRun(JNIEnv *e, jobject service, jint fd) {
    monitor_t m = {.env = e, .service = service, .fd = fd};
    jclass cls = (*e)->GetObjectClass(e, service);
    m.protect = (*e)->GetMethodID(e, cls, "protectSocket", "(I)Z");
    m.opened = (*e)->GetMethodID(e, cls, "connectionOpened", "(Ljava/lang/String;Ljava/lang/String;III)J");
    m.traffic = (*e)->GetMethodID(e, cls, "traffic", "(JJJ)V");
    m.closed = (*e)->GetMethodID(e, cls, "connectionClosed", "(J)V");
    m.info = (*e)->GetMethodID(e, cls, "connectionInfo", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (!m.protect || !m.opened || !m.traffic || !m.closed || !m.info || fd >= FD_SETSIZE) return -1;
    zdtun_callbacks_t callbacks = {.send_client = send_client, .on_socket_open = protect_socket,
            .on_connection_open = opened, .on_connection_close = closed, .account_packet = account};
    zdtun_t *tun = zdtun_init(&callbacks, &m);
    if (!tun) return -1;
    zdtun_set_mtu(tun, 1500);
    unsigned char buffer[65535];
    time_t last = time(NULL);
    while (!atomic_load(&stopping) && !m.failed) {
        fd_set rd, wr;
        FD_ZERO(&rd); FD_ZERO(&wr);
        int maxfd = 0;
        zdtun_fds(tun, &maxfd, &rd, &wr);
        if (maxfd >= FD_SETSIZE) { m.failed = 1; break; }
        FD_SET(fd, &rd);
        if (fd > maxfd) maxfd = fd;
        struct timeval timeout = {.tv_sec = 0, .tv_usec = 250000};
        int n = select(maxfd + 1, &rd, &wr, NULL, &timeout);
        if (n < 0) { if (errno == EINTR) continue; m.failed = 1; break; }
        if (FD_ISSET(fd, &rd)) {
            ssize_t size = read(fd, buffer, sizeof(buffer));
            if (size > 0) zdtun_easy_forward(tun, (char *)buffer, size);
            else if (size == 0 || (errno != EAGAIN && errno != EINTR)) m.failed = 1;
            FD_CLR(fd, &rd);
        }
        zdtun_handle_fd(tun, &rd, &wr);
        time_t now = time(NULL);
        if (now != last) {
            zdtun_iter_connections(tun, flush, NULL);
            zdtun_purge_expired(tun);
            last = now;
        }
        if ((*e)->ExceptionCheck(e)) { (*e)->ExceptionClear(e); m.failed = 1; }
    }
    zdtun_finalize(tun);
    return m.failed ? -1 : 0;
}
