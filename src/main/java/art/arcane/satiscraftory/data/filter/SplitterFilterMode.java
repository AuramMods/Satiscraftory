package art.arcane.satiscraftory.data.filter;

public enum SplitterFilterMode {
    UNASSIGNED, OVERFLOW, WHITELIST, BLACKLIST;

    public SplitterFilterMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
