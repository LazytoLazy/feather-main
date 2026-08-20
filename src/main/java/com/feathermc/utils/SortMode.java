package com.feathermc.utils;

/** Sort options for the Auction House / Order System listing grids. */
public enum SortMode {
    RECENT("&e&lSort: Recently Listed"),
    PRICE_HIGH("&e&lSort: Highest Price"),
    PRICE_LOW("&e&lSort: Lowest Price");

    private final String display;

    SortMode(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    public SortMode next() {
        SortMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
