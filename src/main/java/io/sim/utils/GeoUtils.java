package io.sim.utils;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import java.util.logging.Level; // Import para logging
import java.util.logging.Logger;

public class GeoUtils {
    // Usar java.util.logging para simplicidade aqui e evitar conflitos
    private static final Logger logger = Logger.getLogger(GeoUtils.class.getName());
    private static CoordinateTransform sumoToGeo;
    private static boolean initialized = false;
    // String PROJ4 derivada da tag <location> do seu .net.xml
    // Adicionado "+south" porque as latitudes origBoundary são negativas.
    //private static final String SUMO_CRS_PROJ4_STRING = "+proj=utm +zone=23 +south +ellps=WGS84 +datum=WGS84 +units=m +no_defs";
    private static final String SUMO_CRS_EPSG_CODE = "EPSG:32723"; // Usar código EPSG
    private static final String GEO_CRS_CODE = "EPSG:4326";

    public static synchronized void initialize() {
        if (initialized) {
            // logger.info("GeoUtils já foi inicializado."); // Pode comentar para reduzir logs
            return;
        }
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem sumoCrs = null;
        CoordinateReferenceSystem geoCrs = null;
        
        try {
            logger.info("GeoUtils.initialize(): Inicializando transformação de coordenadas...");
            // logger.info("GeoUtils.initialize(): SUMO CRS PROJ4 String = " + SUMO_CRS_PROJ4_STRING); // Não mais usado diretamente
            logger.info("GeoUtils.initialize(): SUMO CRS Code = " + SUMO_CRS_EPSG_CODE);
            logger.info("GeoUtils.initialize(): GEO CRS Code = " + GEO_CRS_CODE);

            // sumoCrs = crsFactory.createFromParameters("SUMO_UTM23S_WGS84", SUMO_CRS_PROJ4_STRING);
            sumoCrs = crsFactory.createFromName(SUMO_CRS_EPSG_CODE); // << MUDANÇA AQUI
            geoCrs  = crsFactory.createFromName(GEO_CRS_CODE);

            CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
            sumoToGeo = ctFactory.createTransform(sumoCrs, geoCrs); 
            initialized = true;
            logger.info("GeoUtils.initialize(): CoordinateTransform inicializado com sucesso.");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "GeoUtils.initialize(): ERRO CRÍTICO ao inicializar CoordinateTransform: " + e.getMessage(), e);
            sumoToGeo = null; 
            initialized = false; 
        }
    }

    /**
     * Converte coordenadas (x,y) do SUMO para coordenadas geográficas (longitude, latitude).
     *
     * @param x Coordenada X no CRS do SUMO (UTM)
     * @param y Coordenada Y no CRS do SUMO (UTM)
     * @return Array com [longitude, latitude] ou [NaN, NaN] em caso de erro.
     */
    public static double[] convertToGeo(double x, double y) {
        if (sumoToGeo == null) {
            logger.severe("GeoUtils.sumoToGeo não está inicializado! Verifique o bloco estático e os códigos CRS. Coordenadas não convertidas.");
            return new double[] { Double.NaN, Double.NaN };
        }

        ProjCoordinate srcCoord = new ProjCoordinate(x, y);
        ProjCoordinate dstCoord = new ProjCoordinate();

        // Adicionar logs aqui
        if (srcCoord == null) {
            logger.severe("GeoUtils: srcCoord é NULO antes da transformação para x=" + x + ", y=" + y);
            return new double[] { Double.NaN, Double.NaN };
        }
        // logger.info("GeoUtils: srcCoord (x,y) antes da transformação: (" + srcCoord.x + ", " + srcCoord.y + ")"); // Já logado no Car.java
        if (dstCoord == null) {
            // Isto seria muito inesperado, pois dstCoord é instanciado logo acima.
            logger.severe("GeoUtils: dstCoord é NULO antes da transformação para x=" + x + ", y=" + y);
            return new double[] { Double.NaN, Double.NaN };
        }

        try {
            sumoToGeo.transform(srcCoord, dstCoord); // Linha 50 agora (após adicionar logs)
            return new double[] { dstCoord.x, dstCoord.y };
        } catch (Exception e) { // Capturar exceção mais genérica para ver se é NPE ou outra coisa
            logger.log(Level.SEVERE, "GeoUtils: Erro EXATO durante a transformação de (" + x + ", " + y + "): " + e.getClass().getName() + " - " + e.getMessage(), e);
            return new double[] { Double.NaN, Double.NaN };
        }
    }

    public static double calculateEuclideanDistance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }
}