package io.sim.utils;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

public class GeoUtils {
    private static CoordinateTransform sumoToGeo;

    /**
     * Constrói o conversor a partir de dois códigos EPSG (ou PROJ4 strings).
     *
     * @param sumoCrsCode código EPSG (ou PROJ4 string) do sistema de coordenadas do SUMO
     * @param geoCrsCode  código EPSG (normalmente "EPSG:4326" para lon/lat)
     */
    public GeoUtils(String sumoCrsCode, String geoCrsCode) {
        CRSFactory    crsFactory  = new CRSFactory();
        CoordinateReferenceSystem sumoCrs = crsFactory.createFromName(sumoCrsCode);
        CoordinateReferenceSystem geoCrs  = crsFactory.createFromName(geoCrsCode);

        CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
        GeoUtils.sumoToGeo = ctFactory.createTransform(sumoCrs, geoCrs);
    }

    /**
     * Converte coordenadas (x,y) do SUMO para coordenadas geográficas (longitude, latitude).
     *
     * @param x Coordenada X no CRS do SUMO
     * @param y Coordenada Y no CRS do SUMO
     * @return Array com [longitude, latitude]
     */
    public static double[] convertToGeo(double x, double y) {
        ProjCoordinate src = new ProjCoordinate(x, y);
        ProjCoordinate dst = new ProjCoordinate();

        sumoToGeo.transform(src, dst);
        return new double[] { dst.x, dst.y };
    }
}