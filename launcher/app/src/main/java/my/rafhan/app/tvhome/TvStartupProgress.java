package my.rafhan.app.tvhome;

/** The loading cover only advances: late/duplicate page callbacks must not undo media progress. */
final class TvStartupProgress {
    enum Stage {
        LOADING_PAGE("Tune in"),
        PAGE_VISIBLE("Page visible"),
        PAGE_LOADED("Page loaded"),
        PLAYER_READY("Player ready"),
        VIDEO_ATTACHED("Video attached"),
        MEDIA_METADATA_READY("Media metadata ready");

        final String label;

        Stage(String label) { this.label = label; }
    }

    private Stage stage = Stage.LOADING_PAGE;
    private String initialLabel = Stage.LOADING_PAGE.label;

    void reset(String channelName) {
        stage = Stage.LOADING_PAGE;
        initialLabel = tuningLabel(channelName);
    }

    static String tuningLabel(String channelName) { return "Tune in to " + channelName; }

    String label() { return stage == Stage.LOADING_PAGE ? initialLabel : stage.label; }

    boolean advance(Stage next) {
        if (next.ordinal() <= stage.ordinal()) return false;
        stage = next;
        return true;
    }
}
