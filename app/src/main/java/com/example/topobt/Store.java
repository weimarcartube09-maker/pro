package com.example.topobt;

import java.util.ArrayList;
import java.util.List;

/** Datos compartidos entre pantallas: estación y puntos leídos. */
public class Store {
    public static final List<String> names = new ArrayList<>();
    public static final List<double[]> points = new ArrayList<>(); // {N, E, Z}

    // Configuración de la estación
    public static double n0 = 1000, e0 = 1000, z0 = 100;
    public static double az0 = 0;   // azimut que corresponde a Hz = 0
    public static double hi = 0;    // altura del instrumento
    public static double ht = 0;    // altura del prisma

    public static String add(double n, double e, double z) {
        String name = "P" + (points.size() + 1);
        names.add(name);
        points.add(new double[]{n, e, z});
        return name;
    }
}
