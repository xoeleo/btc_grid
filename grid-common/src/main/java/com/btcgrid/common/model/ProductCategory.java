package com.btcgrid.common.model;

public enum ProductCategory {
    SPOT,
    LINEAR,
    INVERSE,
    OPTION;

    public static ProductCategory fromConfig(String raw) {
        return ProductCategory.valueOf(raw.trim().toUpperCase());
    }
}
