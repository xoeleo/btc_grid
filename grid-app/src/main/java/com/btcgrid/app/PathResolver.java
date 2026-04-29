package com.btcgrid.app;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathResolver {

    private PathResolver() {
    }

    public static String absolute(String rawPath) {
        return Paths.get(rawPath).toAbsolutePath().normalize().toString();
    }

    public static Path absolutePath(String rawPath) {
        return Paths.get(absolute(rawPath));
    }
}
