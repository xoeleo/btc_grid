package com.btcgrid.app;

public interface StatusStore {

    void save(RuntimeStatusSnapshot snapshot);
}
