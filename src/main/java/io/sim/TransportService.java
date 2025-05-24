package io.sim;

import de.tudresden.sumo.cmd.Route;
import de.tudresden.sumo.cmd.Vehicle;
import de.tudresden.sumo.objects.SumoStringList;
import it.polito.appeal.traci.SumoTraciConnection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Classe responsável por gerenciar o transporte no sistema de simulação.
 * Inicializa rotas e veículos no SUMO e gerencia o ciclo de vida da simulação.
 */
public class TransportService extends Thread {

    private static final Logger logger = Logger.getLogger(TransportService.class.getName());
    
    private String idTransportService;
    private boolean on_off;
    private SumoTraciConnection sumo;
    private Car car;
    private Rota rota;
    private int maxRetries = 5;
    private int initialRetryDelay = 500; // ms
    
    // Static lock object for synchronizing SUMO access across all TransportService instances
    private static final Object SUMO_LOCK = new Object();
    
    // Static counter to stagger initial connection attempts
    private static int instanceCounter = 0;
    private final int instanceId;

    /**
     * Construtor do TransportService.
     * 
     * @param _on_off Estado inicial (ligado/desligado)
     * @param _idTransportService ID do serviço de transporte
     * @param _rota Rota a ser executada
     * @param _car Carro a ser utilizado
     * @param _sumo Conexão com o SUMO
     */
    public TransportService(boolean _on_off, String _idTransportService, Rota _rota, Car _car,
            SumoTraciConnection _sumo) {

        this.on_off = _on_off;
        this.idTransportService = _idTransportService;
        this.rota = _rota;
        this.car = _car;
        this.sumo = _sumo;
        
        // Assign a unique instance ID for staggered initialization
        synchronized (TransportService.class) {
            this.instanceId = instanceCounter++;
        }
        
        logger.info("TransportService " + idTransportService + " criado para carro " + car.getIdCar() + " e rota " + rota.getIdRota());
    }

    /**
     * Método principal da thread.
     * Inicializa rotas e gerencia o ciclo de vida da simulação.
     */
    @Override
    public void run() {
        try {
            // Stagger initialization to avoid all services trying to connect at once
            // Each instance waits a different amount of time based on its ID
            Thread.sleep(instanceId * 200);
            
            logger.info("TransportService " + idTransportService + " iniciando inicialização de rota");
            boolean routeInitialized = initializeRoutesWithRetry();
            
            if (!routeInitialized) {
                logger.severe("TransportService " + idTransportService + " falhou ao inicializar rotas após " + maxRetries + " tentativas. Abortando.");
                this.on_off = false;
                return;
            }
            
            logger.info("TransportService " + idTransportService + " iniciado com sucesso");

            // Não precisamos mais fazer do_timestep aqui, pois o EnvSimulator já tem uma thread dedicada para isso
            while (this.on_off) {
                try {
                    // Verifica se o veículo ainda está na simulação
                    synchronized (SUMO_LOCK) {
                        if (!this.getSumo().isClosed()) {
                            // Verifica o status do veículo e da rota
                            checkVehicleStatus();
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "TransportService " + idTransportService + " erro durante verificação de status: " + e.getMessage(), e);
                    
                    // Try to recover if possible
                    if (e.getMessage() != null && e.getMessage().contains("Broken pipe")) {
                        logger.warning("TransportService " + idTransportService + " conexão com SUMO perdida. Tentando recuperar...");
                        // Here you could implement reconnection logic if needed
                        // For now, we'll just exit the loop to prevent further errors
                        this.on_off = false;
                    }
                }
                
                // Sleep for acquisition rate
                Thread.sleep(this.car.getAcquisitionRate());
                
                // Check if SUMO is still running
                if (this.getSumo().isClosed()) {
                    this.on_off = false;
                    logger.info("TransportService " + idTransportService + " SUMO está fechado...");
                }
            }

        } catch (InterruptedException e) {
            logger.warning("TransportService " + idTransportService + " interrompido: " + e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "TransportService " + idTransportService + " erro crítico: " + e.getMessage(), e);
        } finally {
            logger.info("TransportService " + idTransportService + " encerrado");
        }
    }

    /**
     * Verifica o status do veículo na simulação.
     * 
     * @throws Exception Se ocorrer um erro durante a verificação
     */
    private void checkVehicleStatus() throws Exception {
        // Aqui você pode adicionar lógica para verificar o status do veículo
        // Por exemplo, verificar se o veículo chegou ao destino, se está parado, etc.
        // Esta é uma implementação simplificada
        
        // Verifica se a rota foi concluída
        if (rota.completeRota()) {
            logger.info("TransportService " + idTransportService + " rota " + rota.getIdRota() + " concluída");
            this.on_off = false;
        }
    }

    /**
     * Tenta inicializar rotas com mecanismo de retry e backoff exponencial.
     * 
     * @return true se bem-sucedido, false caso contrário
     */
    private boolean initializeRoutesWithRetry() {
        int attempts = 0;
        boolean success = false;
        int retryDelay = initialRetryDelay;
        
        while (attempts < maxRetries && !success) {
            try {
                if (attempts > 0) {
                    logger.info("TransportService " + idTransportService + " tentando inicialização de rota novamente (tentativa " + (attempts + 1) + "/" + maxRetries + ")");
                    Thread.sleep(retryDelay);
                    // Exponential backoff: double the delay for each retry
                    retryDelay *= 2;
                }
                
                // Synchronize access to SUMO across all TransportService instances
                synchronized (SUMO_LOCK) {
                    if (!this.getSumo().isClosed()) {
                        initializeRoutes();
                        success = true;
                        logger.info("TransportService " + idTransportService + " inicialização de rota bem-sucedida");
                    } else {
                        throw new Exception("Conexão SUMO está fechada");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning("TransportService " + idTransportService + " interrompido durante inicialização de rota");
                return false;
            } catch (Exception e) {
                attempts++;
                logger.warning("TransportService " + idTransportService + " falha na inicialização de rota: " + e.getMessage());
                
                // Check if SUMO is still running
                if (this.getSumo().isClosed()) {
                    logger.severe("TransportService " + idTransportService + " conexão SUMO está fechada. Não é possível tentar novamente.");
                    break;
                }
            }
        }
        
        return success;
    }

    /**
     * Inicializa rotas no SUMO.
     * 
     * @throws Exception Se ocorrer um erro durante a inicialização
     */
    private void initializeRoutes() throws Exception {
        if (this.getSumo().isClosed()) {
            throw new Exception("Conexão SUMO está fechada. Não é possível inicializar rotas.");
        }

        SumoStringList edge = new SumoStringList();
        edge.clear();
        String[] aux = this.rota.getRota();

        if (aux == null || aux.length < 2) {
            throw new Exception("Dados de rota inválidos. Array de rota é nulo ou muito curto.");
        }

        try {
            // Adiciona os edges à lista
            for (String e : aux) {
                edge.add(e);
            }
            
            // Add the route first
            logger.info("TransportService " + idTransportService + " adicionando rota: " + this.rota.getIdRota() + " com " + edge.size() + " edges");
            sumo.do_job_set(Route.add(this.rota.getIdRota(), edge));
            
            // Then add the vehicle with the route
            logger.info("TransportService " + idTransportService + " adicionando veículo: " + getCar().getIdCar());
            sumo.do_job_set(Vehicle.addFull(getCar().getIdCar(),          //vehID
                                        this.rota.getIdRota(),         //routeID 
                                        "DEFAULT_VEHTYPE",             //typeID 
                                        "now",                         //depart  
                                        "0",                           //departLane 
                                        "0",                           //departPos 
                                        "0",                           //departSpeed
                                        "current",                     //arrivalLane 
                                        "max",                         //arrivalPos 
                                        "current",                     //arrivalSpeed 
                                        "",                            //fromTaz 
                                        "",                            //toTaz 
                                        "",                            //line 
                                        this.car.getPersonCapacity(),  //personCapacity 
                                        this.car.getPersonNumber())    //personNumber
                );
            
            // Set the vehicle color
            logger.info("TransportService " + idTransportService + " definindo cor do veículo");
            sumo.do_job_set(Vehicle.setColor(getCar().getIdCar(), getCar().getColorCar()));
            
            logger.info("TransportService " + idTransportService + " veículo e rota inicializados com sucesso");
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "TransportService " + idTransportService + " erro durante inicialização de rota: " + e.getMessage(), e);
            throw e; // Re-throw to be handled by the retry mechanism
        }
    }

    /**
     * Verifica se o serviço está ativo.
     * 
     * @return true se ativo, false caso contrário
     */
    public boolean isOn_off() {
        return on_off;
    }

    /**
     * Define o estado do serviço.
     * 
     * @param _on_off Novo estado
     */
    public void setOn_off(boolean _on_off) {
        this.on_off = _on_off;
    }

    /**
     * Obtém o ID do serviço de transporte.
     * 
     * @return ID do serviço
     */
    public String getIdTransportService() {
        return this.idTransportService;
    }

    /**
     * Obtém a conexão com o SUMO.
     * 
     * @return Conexão com o SUMO
     */
    public SumoTraciConnection getSumo() {
        return this.sumo;
    }

    /**
     * Obtém o carro associado ao serviço.
     * 
     * @return Carro
     */
    public Car getCar() {
        return car;
    }

    /**
     * Define o carro associado ao serviço.
     * 
     * @param car Novo carro
     */
    public void setCar(Car car) {
        this.car = car;
    }

    /**
     * Obtém a rota associada ao serviço.
     * 
     * @return Rota
     */
    public Rota getRota() {
        return this.rota;
    }
    
    /**
     * Obtém a rota associada ao serviço (método legado).
     * 
     * @return Rota
     */
    public Rota getRot() {
        return this.rota;
    }
}
