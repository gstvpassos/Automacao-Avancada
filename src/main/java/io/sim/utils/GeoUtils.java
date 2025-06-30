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
    // Valores do seu arquivo net.xml
    private static double NET_OFFSET_X_FROM_FILE = -682757.55;
    private static double NET_OFFSET_Y_FROM_FILE = 2543161.55; 
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
            logger.info("GeoUtils.initialize(): Usando NetOffset X (do arquivo) = " + NET_OFFSET_X_FROM_FILE);
            logger.info("GeoUtils.initialize(): Usando NetOffset Y (do arquivo) = " + NET_OFFSET_Y_FROM_FILE);

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
    public static double[] convertToGeo(double internalSumoX, double internalSumoY) {
        if (sumoToGeo == null) {
            logger.severe("GeoUtils.sumoToGeo não está inicializado! Verifique o bloco estático e os códigos CRS. Coordenadas não convertidas.");
            return new double[] { Double.NaN, Double.NaN };
        }
        // Aplicar o netOffset para obter as coordenadas UTM "verdadeiras"
        // Baseado na análise de que o netOffset_x do arquivo é -EastingOfOrigin
        double trueUtmX = internalSumoX - NET_OFFSET_X_FROM_FILE; //  internalSumoX - (-682757.55) = internalSumoX + 682757.55
        // Para Y, a documentação do SUMO geralmente sugere adição.
        // No entanto, o valor de NET_OFFSET_Y_FROM_FILE (2543161.55) é muito baixo para um Northing UTM 23S.
        // Latitudes originais são ~ -22.9 graus. Northing UTM 23S ~ 7.450.000.
        // Se internalSumoY (0 a ~4000) deve mapear para essa faixa, netOffsetY_from_file deveria ser ~7.450.000.
        // O netOffset_y no seu arquivo parece ser um valor já em um sistema local ou um erro.
        // Vamos seguir a documentação do SUMO: internal_coord + offset.
        // Se isso ainda der problemas, a configuração de y da rede é a questão.
        double trueUtmY = internalSumoY + NET_OFFSET_Y_FROM_FILE; 
        //logger.info("GeoUtils: SUMO internal (x,y): (" + internalSumoX + ", " + internalSumoY + 
                   // "). Com netOffset, True UTM (E,N) para proj4j: (" + trueUtmX + ", " + trueUtmY + ")");

        ProjCoordinate srcCoord = new ProjCoordinate(trueUtmX, trueUtmY);
        ProjCoordinate dstCoord = new ProjCoordinate(); 

        try {
            sumoToGeo.transform(srcCoord, dstCoord);
            //logger.info("GeoUtils: Convertido para Lon: " + dstCoord.x + ", Lat: " + dstCoord.y + " (a partir de UTM E:" + trueUtmX + ", N:" + trueUtmY + ")");
            // Verificação de validade básica para lat/lon
            if (dstCoord.x < -180 || dstCoord.x > 180 || dstCoord.y < -90 || dstCoord.y > 90) {
                logger.warning("GeoUtils: Coordenadas Lat/Lon resultantes parecem inválidas: Lon=" + dstCoord.x + ", Lat=" + dstCoord.y);
                // return new double[] { Double.NaN, Double.NaN }; // Opcional: invalidar se fora da faixa
            }
            return new double[] { dstCoord.x, dstCoord.y }; 
        } catch (Exception e) { // Capturar exceção mais genérica
             logger.log(Level.SEVERE, "GeoUtils: Erro durante a transformação de True UTM (" + trueUtmX + ", " + trueUtmY + ") para Geo: " + e.getClass().getName() + " - " + e.getMessage(), e);
            return new double[] { Double.NaN, Double.NaN };
        }
    }

    public static double calculateEuclideanDistance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }
}