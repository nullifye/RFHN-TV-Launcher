package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.List;

/** The TV's physical inputs, as used by the Inputs dropdown and the phone remote. */
final class TvInputs {
    static final class Item {
        final String label;
        final Intent intent;

        Item(String label, Intent intent) {
            this.label = label;
            this.intent = intent;
        }
    }

    private TvInputs() {}

    /** HDMI ports, then AV / other physical inputs, then the built-in tuner as "Live TV". */
    static List<Item> load(Context ctx) {
        List<Item> hdmi = new ArrayList<>(), other = new ArrayList<>(), out = new ArrayList<>();
        Intent sourcePicker = ProjectorSupport.sourcePicker(ctx);
        Item tuner = null;
        TvInputManager tim = ctx.getSystemService(TvInputManager.class);
        List<TvInputInfo> list = tim == null ? new ArrayList<>() : new ArrayList<>(tim.getTvInputList());
        list.sort((a, b) -> a.getId().compareTo(b.getId()));
        for (TvInputInfo info : list) {
            if (info.getParentId() != null || info.isHidden(ctx)) continue;
            CharSequence custom = info.loadCustomLabel(ctx);
            String label = (custom != null ? custom : info.loadLabel(ctx)).toString();
            if (info.isPassthroughInput()) {
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(
                        TvContract.buildChannelUriForPassthroughInput(info.getId()),
                        "vnd.android.cursor.item/channel");
                // Some Allwinner firmware advertises ports but has no standard VIEW handler.
                // Use its source picker instead of offering HDMI entries that cannot open.
                if (sourcePicker != null && i.resolveActivity(ctx.getPackageManager()) == null) continue;
                if (info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(new Item(label, i));
                else if (info.getType() == TvInputInfo.TYPE_COMPOSITE) other.add(new Item("AV", i));
                else other.add(new Item(label, i));
            } else if (tuner == null && info.getType() == TvInputInfo.TYPE_TUNER
                    && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                tuner = new Item("Live TV", new Intent(Intent.ACTION_VIEW).setDataAndType(
                        TvContract.buildChannelsUriForInput(info.getId()), "vnd.android.cursor.dir/channel"));
            }
        }
        out.addAll(hdmi);
        out.addAll(other);
        if (tuner != null) out.add(tuner);
        if (out.isEmpty() && sourcePicker != null) out.add(new Item("Projector inputs", sourcePicker));
        return out;
    }
}
