package io.sim;

import sim.traci4j.src.java.it.polito.appeal.traci.ReadObjectVarQuery.StringListQ;
import sim.traci4j.src.java.it.polito.appeal.traci.Repository;
import sim.traci4j.src.java.it.polito.appeal.traci.protocol.Constants;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import de.tudresden.sumo.cmd.Simulation;

import java.net.Socket;
import java.net.ConnectException;
import de.tudresden.sumo.objects.SumoColor;
import de.tudresden.sumo.objects.SumoStringList;
import io.sim.reporting.ReportingSystem;
import io.sim.utils.GeoUtils;
import it.polito.appeal.traci.SumoTraciConnection;
import sim.traci4j.src.java.it.polito.appeal.traci.Edge;
import sim.traci4j.src.java.it.polito.appeal.traci.Lane;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Classe responsável por gerenciar o ambiente de simulação.
 * Encapsula todos os parâmetros e lógica de simulação.
 */
public class EnvSimulator extends Thread {

    private static final Logger logger = Logger.getLogger(EnvSimulator.class.getName());
    private CountDownLatch serversReadyLatch;
    private CountDownLatch allCarsReadyLatch;
    // Constantes de configuração
    private static final String DEFAULT_SUMO_VEHICLE_TYPE = "DEFAULT_VEHTYPE";
    private int numDrivers = 100;
    // Constantes
    private static final int NUM_DRIVERS = 100;
    private static final int SIMULATION_TIMEOUT_SECONDS = 300; // 5 minutos
    private static final int SIMULATION_STEP_DELAY_MS = 100; // 100ms entre steps
    private static final int MIN_SIMULATION_TIME_SECONDS = 120; // Tempo mínimo de simulação (2 minutos)
    
    // Constantes de robustez e retry
    private static final int DEFAULT_RETRY_ATTEMPTS = 3;
    private static final int DEFAULT_RETRY_DELAY_MS = 1000;
    private static final int SUMO_CONNECTION_TIMEOUT_MS = 10000; // 10 segundos
    private static final int NETWORK_OPERATION_TIMEOUT_MS = 5000; // 5 segundos
    
    // Constantes de performance
    private static final long CAR_SENSOR_ACQUISITION_RATE_MS = 300; // 300ms para melhor performance
    private static final long FUEL_STATION_POLLING_RATE_MS = 500; // 500ms para polling da estação
    private static final int THREAD_POOL_SIZE = 10; // Pool de threads para operações paralelas
    
    private int numCars = 100;
    private int numRoutes = 200;
    private String routeFile = "data/rotas_validas.rou.xml";
    private String bankHost = "localhost";
    private int bankPort = 12345;
    private int companyPort = 12346; // Porta diferente para a MobilityCompany
    private int sumoPort = 8813;     // Porta padrão do SUMO, diferente das outras
    private double initialBalance = 1000.0;
    
    // Cores para os carros
    private final SumoColor[] CAR_COLORS = {
        new SumoColor(255, 0, 0, 126),    // Vermelho
        new SumoColor(0, 255, 0, 126),    // Verde
        new SumoColor(0, 0, 255, 126),    // Azul
        new SumoColor(255, 255, 0, 126),  // Amarelo
        new SumoColor(0, 255, 255, 126),  // Ciano
        new SumoColor(255, 0, 255, 126),  // Magenta
        new SumoColor(128, 128, 128, 126) // Cinza
    };
    
    // Tipos de combustível
    private final int[] FUEL_TYPES = {1, 2, 3, 4}; // 1-diesel, 2-gasoline, 3-ethanol, 4-hybrid
    
    // Preços de combustível
    private final double[] FUEL_PRICES = {5.20, 5.87, 4.59, 5.50};
    
    // Gerador de números aleatórios
    private final Random random = new Random();
    
    // Componentes da simulação
    private SumoTraciConnection sumo;
    private DataInputStream dis;
    private DataOutputStream dos;
    private Repository<Edge> repoEdge;
    private Repository<Lane> repoLane;
    private ReportingSystem reportingSystem;
    private AlphaBank alphaBank;
    private FuelStation fuelStation;
    
    // Objeto de sincronização para operações SUMO
    private final Object sumoLock = new Object();
    private MobilityCompany mobilityCompany;
    
    // Lista de motoristas
    private List<Driver> drivers;
    
     // Controle de simulação
    private AtomicBoolean simulationRunning;
    private Thread simulationThread;
    private CountDownLatch simulationCompleteLatch;
    private long simulationStartTime;
    
    // Mapa para monitorar o estado das threads
    private ConcurrentHashMap<String, ThreadStatus> threadStatusMap = new ConcurrentHashMap<>();
    
    /**
     * Classe para monitorar o status das threads
     */
    private static class ThreadStatus {
        public String name;
        public String type;
        public long lastActive;
        public boolean isAlive;
        
        public ThreadStatus(String name, String type) {
            this.name = name;
            this.type = type;
            this.lastActive = System.currentTimeMillis();
            this.isAlive = true;
        }
        
        public void updateActivity() {
            this.lastActive = System.currentTimeMillis();
        }
    }
    
    /**
     * Construtor padrão.
     */
    public EnvSimulator() {
        // Inicializa os streams e repositórios
        this.dis = new DataInputStream(new ByteArrayInputStream(new byte[0]));
        this.dos = new DataOutputStream(new ByteArrayOutputStream());
        
        // Inicializa o controle de simulação
        this.simulationCompleteLatch = new CountDownLatch(1);
        this.simulationRunning = new AtomicBoolean(false);
        
        // Inicializa a lista de motoristas
        this.drivers = new ArrayList<>();
        
        // Configura o logger para mostrar mais detalhes
        configureLogger();
    }
    
    /**
     * Configura o logger para mostrar mais detalhes
     */
    private void configureLogger() {
        System.setProperty("java.util.logging.SimpleFormatter.format", 
                "[%1$tF %1$tT] [%4$-7s] %5$s %n");
    }
    
    /**
     * Inicia a simulação.
     */
    @Override
    public void start() {
        logger.info("Iniciando simulação");
        
        super.start();
    }

    /**
     * Inicializa o SUMO.
     * * @throws Exception Se ocorrer um erro ao inicializar o SUMO
     */
    private void initSumo() throws Exception {
        logger.info("Inicializando SUMO");
        
        int maxRetries = 3;
        int retryDelay = 2000; // 2 segundos
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                logger.info("Tentativa " + attempt + " de " + maxRetries + " para inicializar SUMO");
                
                // Configura o caminho para o arquivo de configuração do SUMO
                String sumoConfigPath = "map/map.sumo.cfg";
                String sumo_bin = "sumo-gui";
                
                // Inicializa a conexão com o SUMO
                sumo = new SumoTraciConnection(sumo_bin, sumoConfigPath);
                sumo.addOption("start", "1"); // Inicia o SUMO automaticamente
                //sumo.addOption("quit-on-end", "1"); // Encerra o SUMO quando a simulação terminar
                
                // Inicia a conexão com o SUMO
                sumo.runServer(sumoPort);
                
                logger.info("SUMO inicializado com sucesso na tentativa " + attempt);
                return; // Sucesso, sai do loop
                
            } catch (Exception e) {
                lastException = e;
                logger.log(Level.WARNING, "Falha na tentativa " + attempt + " de inicializar SUMO: " + e.getMessage(), e);
                
                if (attempt < maxRetries) {
                    logger.info("Aguardando " + retryDelay + "ms antes da próxima tentativa...");
                    try {
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Aumenta o delay progressivamente
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Inicialização do SUMO interrompida", ie);
                    }
                }
            }
        }
        
        // Se chegou aqui, todas as tentativas falharam
        logger.log(Level.SEVERE, "Falha ao inicializar SUMO após " + maxRetries + " tentativas", lastException);
        throw new Exception("Erro ao iniciar SUMO após " + maxRetries + " tentativas: " + 
                          (lastException != null ? lastException.getMessage() : "Erro desconhecido"), lastException);
    }
    
    /**
     * Inicializa o AlphaBank.
     */
    private void initAlphaBank(CountDownLatch readyLatch) {
        logger.info("Inicializando AlphaBank");
        
        try {
            // Cria e inicia o AlphaBank
            alphaBank = new AlphaBank(bankPort, readyLatch);
            alphaBank.start();
            
            logger.info("AlphaBank inicializado com sucesso");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao inicializar AlphaBank", e);
        }
    }
    
    /**
     * Inicializa a MobilityCompany.
     */
    private void initMobilityCompany(CountDownLatch readyLatch) {
        logger.info("Inicializando MobilityCompany");
        // Cria uma conta para a empresa
        Account companyAccount = new Account("company_password", "mobility_company", 10000.0);
        logger.info("EnvSimulator - initCompany: Criada companyAccount com LOGIN: '" + companyAccount.getLogin() + "', SENHA: '" + companyAccount.getSenha() + "'");
        if (alphaBank != null) {
            boolean added = alphaBank.addAccount(companyAccount);
            logger.info("EnvSimulator - initCompany: companyAccount adicionada ao AlphaBank? " + added + ". Login usado para adicionar: '" + companyAccount.getLogin() + "'");
        } else {
            logger.severe("EnvSimulator - initCompany: alphaBank é NULO antes de adicionar companyAccount!");
        }
        try {
            // Cria e inicia a MobilityCompany
            mobilityCompany = new MobilityCompany("mobility_company", companyPort, alphaBank, 
                                    companyAccount, routeFile, 
                                    bankHost, bankPort, readyLatch); // Certifique-se de passar bankHost e bankPort

            mobilityCompany.start();
            
            logger.info("MobilityCompany inicializada com sucesso");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao inicializar MobilityCompany", e);
        }
    }
    
    /**
     * Inicializa o sistema de relatórios.
     */
    private void initReportingSystem() {
        logger.info("Inicializando sistema de relatórios");
        
        try {
            // Cria e inicia o sistema de relatórios
            reportingSystem = io.sim.reporting.ReportingSystem.getInstance(mobilityCompany);
            reportingSystem.start();
            
            logger.info("Sistema de relatórios inicializado com sucesso");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao inicializar sistema de relatórios", e);
        }
    }
    
    /**
     * Inicializa o posto de combustível.
     */
    private void initFuelStation() {
        logger.info("Inicializando posto de combustível");
        // Cria uma conta para o posto
        Account fuelStationAccount = new Account("fuel_password", "fuel_station", 0.0);
        logger.info("EnvSimulator - initFuelStation: Criada fuelStationAccount com LOGIN: '" + fuelStationAccount.getLogin() + "', SENHA: '" + fuelStationAccount.getSenha() + "'");
        if (alphaBank != null) {
        boolean added = alphaBank.addAccount(fuelStationAccount);
        logger.info("EnvSimulator - initFuelStation: fuelStationAccount adicionada ao AlphaBank? " + added + ". Login usado para adicionar: '" + fuelStationAccount.getLogin() + "'");
    } else {
        logger.severe("EnvSimulator - initFuelStation: alphaBank é NULO antes de adicionar fuelStationAccount!");
    }
        try {
            // Cria e inicia o posto de combustível
            fuelStation = new FuelStation("FuelStation01", "Main Fuel Stop", 
                              bankHost, bankPort, // Passa o host e porta do AlphaBank
                              fuelStationAccount.getLogin(), 
                              fuelStationAccount.getSenha(), 
                              fuelStationAccount.getBalance());
            fuelStation.start();
            
            logger.info("Posto de combustível inicializado com sucesso");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao inicializar posto de combustível", e);
        }
    }

    /**
     * Construtor com parâmetros personalizados.
     * * @param numDrivers Número de motoristas
     * @param numCars Número de carros
     * @param numRoutes Número de rotas
     * @param routeFile Arquivo de rotas
     * @param bankHost Host do banco
     * @param bankPort Porta do banco
     * @param companyPort Porta da empresa
     * @param sumoPort Porta do SUMO
     * @param initialBalance Saldo inicial
     */
    public EnvSimulator(int numDrivers, int numCars, int numRoutes, String routeFile,
                        String bankHost, int bankPort, int companyPort, int sumoPort, double initialBalance) {
        this();
        this.numDrivers = numDrivers;
        this.numCars = numCars;
        this.numRoutes = numRoutes;
        this.routeFile = routeFile;
        this.bankHost = bankHost;
        this.bankPort = bankPort;
        this.companyPort = companyPort;
        this.sumoPort = sumoPort;
        this.initialBalance = initialBalance;
    }
    
    /**
     * Método principal da thread.
     */
    @Override
    public void run() {
        logger.info("Iniciando simulação SUMO com " + numDrivers + " Drivers, " + 
                   numCars + " Cars e " + numRoutes + " Routes");
        GeoUtils.initialize(); 
        logger.info("GeoUtils.initialize() chamado a partir do EnvSimulator.");
        try {
            this.serversReadyLatch = new CountDownLatch(2);
            // Inicializa todos os componentes
            initializeComponents();
            logger.info("Aguardando servidores AlphaBank e MobilityCompany ficarem totalmente prontos...");
            boolean serversReady = serversReadyLatch.await(30, TimeUnit.SECONDS);
            if (!serversReady) {
                throw new RuntimeException("TIMEOUT: Servidores não iniciaram a tempo!");
            }
            logger.info("Servidores confirmaram prontidão! Iniciando criação dos clientes...");

            this.allCarsReadyLatch = new CountDownLatch(numCars);

            // Verifica se o SUMO está rodando
            boolean sumoIsRunning;
            // MODIFICAÇÃO: Adicionado synchronized
            synchronized(sumoLock) {
                sumoIsRunning = (sumo == null || sumo.isClosed());
            }

            if (sumoIsRunning) {
                logger.severe("SUMO não está rodando. Abortando simulação.");
                return;
            }
            logger.info("Fase 1: Criando objetos Car e Driver e registrando veículos no SUMO...");
            drivers = createDriversAndCars();

            startSimulationLoop();
            logger.info("Loop de simulação iniciado com sucesso");
            
            Thread.sleep(2000);
            
            startThreadMonitoring();
            
            startDriversandCars(drivers);
            logger.info("Todos os motoristas iniciados com sucesso");
            
            boolean allReady = allCarsReadyLatch.await(60, TimeUnit.SECONDS); // Timeout de 60 segundos

            if (allReady) {
                logger.info("TODOS OS CARROS PRONTOS! A SIMULAÇÃO CONTINUA.");
                // A simulação agora roda livremente, pois o latch em simulationThread foi liberado.
            } else {
                logger.severe("TIMEOUT! Nem todos os carros ficaram prontos a tempo. Abortando.");
                simulationRunning.set(false); // Para a simulação
            }

            waitForDriversCompletion(drivers);
            
            logger.info("Aguardando 10 segundos adicionais para coleta final de dados...");
            Thread.sleep(10000);
            
            generateFinalReports();
            
            logger.info("Simulação concluída com sucesso");
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro durante a simulação", e);
        } finally {
            shutdownServices();
        }
    }
    
    /**
     * Inicia o monitoramento de threads para detectar deadlocks e threads paradas
     */
    private void startThreadMonitoring() {
        Thread monitorThread = new Thread(() -> {
            logger.info("Iniciando monitoramento de threads");
            
            while (simulationRunning.get()) {
                try {
                    for (Map.Entry<String, ThreadStatus> entry : threadStatusMap.entrySet()) {
                        ThreadStatus status = entry.getValue();
                        
                        long inactiveTime = System.currentTimeMillis() - status.lastActive;
                        if (inactiveTime > 30000 && status.isAlive) {
                            logger.warning("Thread " + status.name + " (" + status.type + ") está inativa há " + 
                                          (inactiveTime / 1000) + " segundos");
                        }
                    }
                    
                    ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
                    ThreadGroup parentGroup;
                    while ((parentGroup = rootGroup.getParent()) != null) {
                        rootGroup = parentGroup;
                    }
                    
                    Thread[] threads = new Thread[rootGroup.activeCount()];
                    while (rootGroup.enumerate(threads, true) == threads.length) {
                        threads = new Thread[threads.length * 2];
                    }
                    
                    int blockedCount = 0;
                    for (Thread t : threads) {
                        if (t != null) {
                            Thread.State state = t.getState();
                            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING) {
                                blockedCount++;
                            }
                        }
                    }
                    
                    if (blockedCount > 10) {
                        logger.warning("Possível deadlock detectado: " + blockedCount + " threads bloqueadas");
                    }
                    
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro no monitoramento de threads", e);
                }
            }
            
            logger.info("Monitoramento de threads encerrado");
        });
        
        monitorThread.setName("ThreadMonitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }
    
    /**
     * Inicializa todos os componentes da simulação
     */
    private void initializeComponents() throws Exception {
        logger.info("Inicializando componentes da simulação");
        
        initSumo();
        initAlphaBank(serversReadyLatch);        
        initMobilityCompany(serversReadyLatch);
        initReportingSystem();
        initFuelStation();
        
        // MUDANÇA #1: Em vez de um sleep fixo, esperamos ativamente pelo AlphaBank
        boolean bankReady = waitForServer(this.bankHost, this.bankPort, 15); // Timeout de 15 segundos

        // MUDANÇA #2: Adicionamos uma espera ATIVA pela MobilityCompany também
        boolean companyReady = waitForServer("localhost", this.companyPort, 15); // Usando localhost e a porta da companhia

        // MUDANÇA #3: Verificamos se os servidores realmente iniciaram
        if (!bankReady || !companyReady) {
            throw new RuntimeException("Falha na inicialização: Um ou mais servidores não ficaram prontos a tempo.");
        }
        
        logger.info("Todos os componentes e servidores inicializados com sucesso e estão online.");
    
    }

    private boolean waitForServer(String host, int port, int timeoutSeconds) {
        logger.info("Aguardando servidor em " + host + ":" + port + " ficar online...");
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try (Socket socket = new Socket(host, port)) {
                // Se a linha acima executou sem erro, a porta está aberta.
                logger.info("Sucesso! Servidor em " + host + ":" + port + " está online.");
                return true; // Servidor está pronto
            } catch (ConnectException e) {
                // "Connection refused" é esperado, significa que o servidor ainda não está escutando.
                try {
                    Thread.sleep(500); // Espera meio segundo antes de tentar de novo
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } catch (IOException e) {
                // Outros erros de IO
                logger.log(Level.WARNING, "Erro de IO ao tentar conectar em " + host + ":" + port, e);
                return false;
            }
        }

        logger.severe("TIMEOUT! Servidor em " + host + ":" + port + " não ficou online em " + timeoutSeconds + " segundos.");
        return false; // Timeout atingido
    }

    private void startSimulationLoop() {
        logger.info("Iniciando loop de simulação do SUMO com gerenciamento de ciclo de vida.");
        
        simulationRunning.set(true);
        
        simulationThread = new Thread(() -> {
            try {
                logger.info("Loop de simulação aguardando a inicialização de todos os carros...");
                allCarsReadyLatch.await(); // Espera até a contagem chegar a zero
                logger.info("Loop de simulação liberado! Iniciando os passos do SUMO.");
            
                logger.info("Loop de simulação do SUMO iniciado.");
                int step = 0;
                // NOVO: Conjunto para rastrear os veículos que estavam ativos no passo anterior
                java.util.Set<String> vehiclesInLastStep = new java.util.HashSet<>();

                while (simulationRunning.get()) {
                    boolean isClosed = true;
                    java.util.Set<String> currentActiveVehicles = new java.util.HashSet<>();

                    // Bloco sincronizado para todas as operações do passo
                    synchronized (sumoLock) {
                        if (sumo != null && !sumo.isClosed()) {
                            isClosed = false;
                            try {
                                sumo.do_timestep();
                                step++;
                                
                                // Obtém a lista atual de veículos na simulação
                                SumoStringList vehicleList = (SumoStringList) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getIDList());
                                currentActiveVehicles.addAll(vehicleList);

                                if (step % 100 == 0) {
                                    double simTime = (double) sumo.do_job_get(Simulation.getTime());
                                    logger.info("Simulação em andamento - Step: " + step + ", Tempo: " + simTime + ", Veículos Ativos: " + currentActiveVehicles.size());
                                }
                            } catch (Exception e) {
                                if (simulationRunning.get()) {
                                    logger.log(Level.WARNING, "Erro no passo de simulação", e);
                                }
                            }
                        }
                    }

                    if (isClosed) {
                        logger.severe("Conexão com o SUMO foi perdida. Encerrando o loop.");
                        simulationRunning.set(false);
                        break; 
                    }

                    // ===== LÓGICA DE GERENCIAMENTO DE THREADS (A Correção Chave) =====
                    // Compara a lista de veículos do passo anterior com a atual para encontrar quem saiu
                    java.util.Set<String> vehiclesThatLeft = new java.util.HashSet<>(vehiclesInLastStep);
                    vehiclesThatLeft.removeAll(currentActiveVehicles);

                    if (!vehiclesThatLeft.isEmpty()) {
                        logger.info("Veículos que completaram a rota ou saíram da simulação: " + vehiclesThatLeft);
                        for (String vehicleId : vehiclesThatLeft) {
                            // Encontra o Driver associado ao carro que saiu
                            for (Driver driver : drivers) {
                                if (driver.getCar() != null && driver.getCar().getIdCar().equals(vehicleId)) {
                                    logger.info("Notificando Driver " + driver.getDriverId() + " (Carro: " + vehicleId + ") para encerrar sua thread.");
                                    driver.stopDriver(); // Comanda o encerramento gracioso da thread do Driver
                                    break; 
                                }
                            }
                        }
                    }
                    // Atualiza o conjunto de veículos para a próxima iteração
                    vehiclesInLastStep = currentActiveVehicles;
                    // =================================================================

                    // ===== LÓGICA DE ENCERRAMENTO DA SIMULAÇÃO =====
                    long elapsedTimeSeconds = (System.currentTimeMillis() - simulationStartTime) / 1000;
                    
                    // Condições para encerrar a simulação inteira:
                    // 1. Timeout global foi atingido.
                    // 2. OU: O tempo mínimo de simulação passou, todos os motoristas foram criados, E não há mais carros ativos.
                    if (elapsedTimeSeconds > SIMULATION_TIMEOUT_SECONDS) {
                        logger.info("Timeout da simulação atingido ("+ SIMULATION_TIMEOUT_SECONDS +"s). Encerrando.");
                        simulationRunning.set(false);
                    } else if (drivers.size() >= numDrivers && currentActiveVehicles.isEmpty() && elapsedTimeSeconds > 20) { // Adicionado um tempo mínimo de 20s
                        logger.info("Todos os veículos completaram suas missões. Encerrando a simulação.");
                        simulationRunning.set(false);
                    }
                    
                    // Pausa para não sobrecarregar a CPU
                    Thread.sleep(SIMULATION_STEP_DELAY_MS);
                }
                
                logger.info("Loop de simulação do SUMO finalizado.");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Erro fatal no loop de simulação", e);
                simulationRunning.set(false);
            } finally {
                // Garante que todas as threads de motoristas sejam paradas no final
                for(Driver driver : drivers) {
                    if(driver.isAlive()) {
                        driver.stopDriver();
                    }
                }
            }
        });
        
        simulationThread.setName("SimulationLoop");
        simulationThread.start();
        
        simulationStartTime = System.currentTimeMillis();
    }
    
    /**
     * Cria os motoristas e carros
     */
    private List<Driver> createDriversAndCars() {
        logger.info("Criando " + numDrivers + " motoristas e " + numCars + " carros.");
        List<Driver> driversList = new ArrayList<>();
        List<String> failedCarAdditions = new ArrayList<>(); 

        List<Rota> definicoesDeRotasDisponiveis = mobilityCompany.getAvailableRotas();
        
        if (definicoesDeRotasDisponiveis.isEmpty()) {
            logger.severe("Não há rotas disponíveis da MobilityCompany para distribuir aos motoristas");
            return driversList;
        }
        logger.info("Distribuindo " + definicoesDeRotasDisponiveis.size() + " definições de rotas para " + numCars + " carros");
        
        int nextRouteDefinitionIndex = 0;

        for (int i = 0; i < numCars; i++) {
            String carId = String.format("CAR%03d", i + 1);
            String driverId = String.format("DRIVER%03d", i + 1);
            SumoColor carColor = CAR_COLORS[random.nextInt(CAR_COLORS.length)];

            try {
                // MODIFICAÇÃO: Adicionado synchronized
                synchronized (sumoLock) {
                    if (sumo == null || sumo.isClosed()) {
                        logger.severe("Conexão SUMO perdida antes de adicionar " + carId);
                        failedCarAdditions.add(carId + " (conexão SUMO fechada)");
                        break; 
                    }
                }

                Rota definicaoRotaInicial = definicoesDeRotasDisponiveis.get(nextRouteDefinitionIndex++);
                
                List<String> arestasDaRotaList = definicaoRotaInicial.getEdgesList();
                if (arestasDaRotaList == null || arestasDaRotaList.isEmpty()) {
                    logger.severe("Definição de rota '" + definicaoRotaInicial.getIdRota() + "' para " + carId + " não tem arestas.");
                    failedCarAdditions.add(carId + " (rota inicial vazia)");
                    continue;
                }
                SumoStringList sumoEdgesIniciais = new SumoStringList();
                sumoEdgesIniciais.addAll(arestasDaRotaList);

                String sumoRouteIdParaCarro = "initial_sumo_route_" + carId; 
                logger.info("Adicionando rota inicial ao SUMO: ID=" + sumoRouteIdParaCarro + " para " + carId + 
                            " (baseada na definição " + definicaoRotaInicial.getIdRota() + ") com arestas: " + sumoEdgesIniciais);
                
                boolean routeAdded = executeSumoOperationWithRetry(
                    "Adicionar rota " + sumoRouteIdParaCarro,
                    () -> {
                        // O synchronized agora está dentro de executeSumoOperationWithRetry
                        try {
                            this.sumo.do_job_set(de.tudresden.sumo.cmd.Route.add(sumoRouteIdParaCarro, sumoEdgesIniciais));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    3
                );
                
                if (!routeAdded) {
                    logger.log(Level.SEVERE, "Falha ao adicionar rota " + sumoRouteIdParaCarro + " após múltiplas tentativas");
                    failedCarAdditions.add(carId + " (falha ao adicionar rota)");
                    continue;
                }
                
                int currentSimTimeSeconds = 0;
                // MODIFICAÇÃO: Adicionado synchronized
                synchronized (sumoLock) {
                    Object timeObj = sumo.do_job_get(de.tudresden.sumo.cmd.Simulation.getCurrentTime());
                    if (timeObj instanceof Integer) { currentSimTimeSeconds = ((Integer) timeObj) / 1000;}
                    else if (timeObj instanceof Double) { currentSimTimeSeconds = (int)(((Double) timeObj).doubleValue() / 1000.0); }
                }
                
                int departOffset = 3; 
                double staggerPerCar = 0.3;
                int departTimeForSumo = currentSimTimeSeconds;// + departOffset + (int)Math.ceil(i * staggerPerCar);
                
                logger.info("Tentando adicionar " + carId + " ao SUMO com tipo " + DEFAULT_SUMO_VEHICLE_TYPE +
                            " na rota SUMO ID " + sumoRouteIdParaCarro + " no tempo " + departTimeForSumo + "s.");
                
                boolean vehicleAdded = executeSumoOperationWithRetry(
                    "Adicionar veículo " + carId,
                    () -> {
                        try {
                            this.sumo.do_job_set(de.tudresden.sumo.cmd.Vehicle.add(
                                carId, DEFAULT_SUMO_VEHICLE_TYPE, sumoRouteIdParaCarro,
                                departTimeForSumo, 0.0, 0.0, (byte) 0));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    3
                );
                
                if (!vehicleAdded) {
                    logger.log(Level.SEVERE, "Falha ao adicionar veículo " + carId + " após múltiplas tentativas");
                    failedCarAdditions.add(carId + " (falha ao adicionar veículo)");
                    continue;
                }
                
                boolean colorSet = executeSumoOperationWithRetry(
                    "Definir cor do veículo " + carId,
                    () -> {
                        try {
                            this.sumo.do_job_set(de.tudresden.sumo.cmd.Vehicle.setColor(carId, carColor));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    2
                );
                
                if (colorSet) {
                    logger.info("Veículo " + carId + " adicionado ao SUMO com sucesso e cor definida.");
                } else {
                    logger.warning("Veículo " + carId + " adicionado mas falha ao definir cor.");
                }
                int fuelType = 2;
                int fuelPreferential = 2;
                double fuelPrice = 5.87;
                int personCapacity = 1;
                int personNumber = 1; 
                Car car = new Car(dis, dos, repoEdge, repoLane, true, carId, carColor, driverId, sumo, 
                                sumoLock, CAR_SENSOR_ACQUISITION_RATE_MS, fuelType, fuelPreferential, fuelPrice, 
                                personCapacity, personNumber, this.allCarsReadyLatch);
                
                try {
                    if (car.isConnected()) {
                        logger.info("EnvSimulator: Aguardando " + 20 + "ms antes de iniciar thread para Car " + car.getIdCar());
                        
                        Thread.sleep(20);
                        
                        boolean vehicleReady = verifyVehicleInSumo(carId);
                        if (vehicleReady) {
                            logger.info("EnvSimulator: Iniciando thread para Car " + car.getIdCar() + " APÓS verificação no SUMO.");
                            //car.start();
                        } else {
                            logger.warning("EnvSimulator: Veículo " + carId + " não está pronto no SUMO. Thread não iniciada.");
                            failedCarAdditions.add(carId + " (veículo não pronto no SUMO)");
                        }
                    } else {
                        //logger.warning("EnvSimulator: Car " + car.getIdCar() + " não conectou à Company.");
                        //failedCarAdditions.add(carId + " (falha na conexão com Company)");
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.WARNING, "EnvSimulator: Delay interrompido para Car " + car.getIdCar(), e);
                    Thread.currentThread().interrupt();
                    break;
                }

                if (driversList.size() < numDrivers) {
                    Driver driver = new Driver(driverId, "Driver " + (i + 1), car,
                                            "localhost", 12345,
                                            driverId.toLowerCase(), "password" + (i + 1),
                                            1000.0, sumo, this, alphaBank);
                    
                    driversList.add(driver);
                    threadStatusMap.put(driverId, new ThreadStatus(driverId, "Driver"));
                }

            } catch (IllegalStateException eState) {
                logger.log(Level.SEVERE, "Falha ao adicionar/configurar veículo " + carId + " - Conexão SUMO fechada: " + eState.getMessage(), eState);
                failedCarAdditions.add(carId + " (conexão SUMO fechada)");
                break; 
            } catch (Exception eLoop) {
                logger.log(Level.SEVERE, "Erro geral no loop de criação para " + carId + ": " + eLoop.getMessage(), eLoop);
                failedCarAdditions.add(carId + " (exceção geral)");
            }
        }

        if (!failedCarAdditions.isEmpty()) {
            logger.warning("Os seguintes carros falharam na adição ao SUMO ou configuração: " + String.join(", ", failedCarAdditions));
        }
        logger.info(driversList.size() + " motoristas criados. " + (numCars - failedCarAdditions.size()) + " carros foram tentados para adicionar ao SUMO.");
        return driversList;
    }
    
    /**
     * Inicia todos os motoristas
     */
    private void startDriversandCars(List<Driver> driversList) {
        logger.info("Iniciando " + driversList.size() + " motoristas");
        
        int driversStarted = 0;
        
        for (Driver driver : driversList) {
            try {
                if (driver.getCar() != null) {
                driver.getCar().start();
                }
                driver.start();
                driversStarted++;
                
                if (driversStarted % 10 == 0) {
                    logger.info(driversStarted + " motoristas iniciados");
                }
                
                Thread.sleep(50);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Erro ao iniciar motorista " + driver.getDriverId(), e);
            }
        }
        
        logger.info("Total de " + driversStarted + " motoristas iniciados com sucesso");
    }
    
    /**
     * Aguarda a conclusão de todos os motoristas
     */
    private void waitForDriversCompletion(List<Driver> driversList) {
        logger.info("Aguardando a conclusão de todos os motoristas");
        
        try {
            long startTime = System.currentTimeMillis();
            long timeout = SIMULATION_TIMEOUT_SECONDS * 1000;
            
            while (System.currentTimeMillis() - startTime < timeout) {
                int activeDrivers = 0;
                
                for (Driver driver : driversList) {
                    if (driver.isAlive()) {
                        activeDrivers++;
                    }
                }
                
                if (activeDrivers == 0) {
                    logger.info("Todos os motoristas concluíram suas rotas");
                    break;
                }
                
                if ((System.currentTimeMillis() - startTime) % 10000 < 1000) {
                    logger.info("Aguardando a conclusão de " + activeDrivers + " motoristas");
                }
                
                Thread.sleep(1000);
            }
            
            if (System.currentTimeMillis() - startTime >= timeout) {
                logger.warning("Timeout atingido. Alguns motoristas ainda estão ativos.");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erro ao aguardar a conclusão dos motoristas", e);
        }
    }
    
    /**
     * Gera relatórios finais
     */
    private void generateFinalReports() {
        logger.info("Gerando relatórios finais");
        
        try {
            logger.info("Aguardando 5 segundos adicionais para garantir que todos os dados foram coletados...");
            Thread.sleep(5000);
            
            if (reportingSystem != null) {
                reportingSystem.generateFinalReports();
                logger.info("Relatórios finais gerados com sucesso");
            } else {
                logger.warning("Sistema de relatórios não está disponível");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erro ao gerar relatórios finais", e);
        }
    }
    
    /**
     * Encerra todos os serviços
     */
    private void shutdownServices() {
        logger.info("Encerrando todos os serviços");
        
        try {
            simulationRunning.set(false);
            
            if (simulationThread != null && simulationThread.isAlive()) {
                try {
                    simulationThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            logger.info("Aguardando 5 segundos adicionais para garantir que todos os dados foram coletados...");
            Thread.sleep(5000);
            
            if (reportingSystem != null) {
                try {
                    reportingSystem.stop();
                    logger.info("Sistema de relatórios encerrado com sucesso");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro ao encerrar sistema de relatórios", e);
                }
            }
            
            if (mobilityCompany != null) {
                try {
                    mobilityCompany.stopServer();
                    logger.info("MobilityCompany encerrada com sucesso");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro ao encerrar MobilityCompany", e);
                }
            }
            
            if (fuelStation != null) {
                try {
                    fuelStation.stopStation();
                    logger.info("Posto de combustível encerrado com sucesso");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro ao encerrar posto de combustível", e);
                }
            }
            
            if (alphaBank != null) {
                try {
                    alphaBank.stopServer();
                    logger.info("AlphaBank encerrado com sucesso");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro ao encerrar AlphaBank", e);
                }
            }
            
            // MODIFICAÇÃO: Adicionado synchronized
            synchronized(sumoLock) {
                if (sumo != null && !sumo.isClosed()) {
                    try {
                        sumo.close();
                        logger.info("SUMO encerrado com sucesso");
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Erro ao encerrar SUMO", e);
                    }
                }
            }
            
            logger.info("Todos os serviços encerrados com sucesso");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao encerrar serviços", e);
        }
    }
    
    /**
     * Atualiza o status de uma thread
     */
    public void updateThreadStatus(String threadId) {
        ThreadStatus status = threadStatusMap.get(threadId);
        if (status != null) {
            status.updateActivity();
        }
    }
    
    /**
     * Marca uma thread como encerrada
     */
    public void markThreadTerminated(String threadId) {
        ThreadStatus status = threadStatusMap.get(threadId);
        if (status != null) {
            status.isAlive = false;
            logger.info("Thread " + threadId + " marcada como encerrada");
        }
    }
    
    /**
     * Verifica se um veículo está disponível e pronto no SUMO.
     * * @param vehicleId ID do veículo a verificar
     * @return true se o veículo está pronto, false caso contrário
     */
    private boolean verifyVehicleInSumo(String vehicleId) {
        try {
            // Este método já estava correto, usando o lock. Nenhuma alteração necessária aqui.
            synchronized(sumoLock) {
                sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getSpeed(vehicleId));
                logSystemDiagnostics("SUMO", "VEHICLE_VERIFIED", "Veículo " + vehicleId + " verificado com sucesso");
                return true;
            }
        } catch (Exception e) {
            logSystemDiagnostics("SUMO", "VEHICLE_NOT_READY", 
                               "Veículo " + vehicleId + " não está pronto: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Obtém o objeto de sincronização SUMO para uso em outras classes.
     * * @return Objeto de lock para sincronização de operações SUMO
     */
    public Object getSumoLock() {
        return sumoLock;
    }
    
    /**
     * Verifica se a conexão SUMO está ativa e funcionando.
     * * @return true se a conexão está ativa, false caso contrário
     */
    private boolean isSumoConnectionActive() {
        // MODIFICAÇÃO: Adicionado synchronized
        synchronized (sumoLock) {
            if (sumo == null || sumo.isClosed()) {
                return false;
            }
            
            try {
                sumo.do_job_get(de.tudresden.sumo.cmd.Simulation.getCurrentTime());
                return true;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Conexão SUMO não está ativa: " + e.getMessage());
                return false;
            }
        }
    }
    
    /**
     * Log estruturado com métricas de performance.
     */
    private void logPerformanceMetrics(Level level, String operation, long duration, boolean success, String details) {
        String status = success ? "SUCCESS" : "FAILED";
        String message = String.format("[PERFORMANCE] %s | %s | %dms | %s", 
                                     operation, status, duration, details != null ? details : "");
        logger.log(level, message);
        
        if (duration > NETWORK_OPERATION_TIMEOUT_MS) {
            logger.warning(String.format("[SLOW_OPERATION] %s took %dms (threshold: %dms)", 
                                       operation, duration, NETWORK_OPERATION_TIMEOUT_MS));
        }
    }
    
    /**
     * Log estruturado para diagnóstico de sistema.
     */
    private void logSystemDiagnostics(String component, String event, String details) {
        String message = String.format("[DIAGNOSTICS] %s | %s | %s", component, event, details);
        logger.info(message);
    }
    
    /**
     * Executa uma operação SUMO com retry logic.
     */
    private boolean executeSumoOperationWithRetry(String operation, Runnable sumoOperation, int maxRetries) {
        long operationStartTime = System.currentTimeMillis();
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // MODIFICAÇÃO: A chamada a isSumoConnectionActive() já é sincronizada.
                if (!isSumoConnectionActive()) {
                    logSystemDiagnostics("SUMO", "CONNECTION_INACTIVE", 
                                       String.format("Tentativa %d/%d para: %s", attempt, maxRetries, operation));
                    if (attempt < maxRetries) {
                        Thread.sleep(DEFAULT_RETRY_DELAY_MS);
                        continue;
                    } else {
                        long totalDuration = System.currentTimeMillis() - operationStartTime;
                        logPerformanceMetrics(Level.SEVERE, operation, totalDuration, false, 
                                            "Conexão SUMO inativa após " + maxRetries + " tentativas");
                        return false;
                    }
                }
                
                long attemptStartTime = System.currentTimeMillis();
                
                // MODIFICAÇÃO: O synchronized foi adicionado AQUI para proteger a operação.
                synchronized(sumoLock) {
                    sumoOperation.run();
                }

                long attemptDuration = System.currentTimeMillis() - attemptStartTime;
                long totalDuration = System.currentTimeMillis() - operationStartTime;
                
                logPerformanceMetrics(Level.INFO, operation, totalDuration, true, 
                                    String.format("Sucesso na tentativa %d (tentativa: %dms)", attempt, attemptDuration));
                return true;
                
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("connection is closed")) {
                    logSystemDiagnostics("SUMO", "CONNECTION_CLOSED", 
                                       String.format("Tentativa %d/%d para: %s - %s", attempt, maxRetries, operation, e.getMessage()));
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(DEFAULT_RETRY_DELAY_MS * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            long totalDuration = System.currentTimeMillis() - operationStartTime;
                            logPerformanceMetrics(Level.WARNING, operation, totalDuration, false, "Operação interrompida");
                            return false;
                        }
                    }
                } else {
                    long totalDuration = System.currentTimeMillis() - operationStartTime;
                    logPerformanceMetrics(Level.SEVERE, operation, totalDuration, false, 
                                        "Erro inesperado: " + e.getMessage());
                    return false;
                }
            } catch (RuntimeException e) {
                Throwable cause = e.getCause();
                String errorDetails = cause != null ? cause.getMessage() : e.getMessage();
                logSystemDiagnostics("SUMO", "RUNTIME_ERROR", 
                                   String.format("Tentativa %d/%d para: %s - %s", attempt, maxRetries, operation, errorDetails));
                
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(DEFAULT_RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            } catch (Exception e) {
                logSystemDiagnostics("SUMO", "GENERAL_ERROR", 
                                   String.format("Tentativa %d/%d para: %s - %s", attempt, maxRetries, operation, e.getMessage()));
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(DEFAULT_RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        
        long totalDuration = System.currentTimeMillis() - operationStartTime;
        logPerformanceMetrics(Level.SEVERE, operation, totalDuration, false, 
                            String.format("Falha após %d tentativas", maxRetries));
        return false;
    }
    
    /**
     * Obtém a MobilityCompany
     */
    public MobilityCompany getMobilityCompany() {
        return mobilityCompany;
    }
    
    /**
     * Obtém o AlphaBank
     */
    public AlphaBank getAlphaBank() {
        return alphaBank;
    }
    
    /**
     * Obtém o posto de combustível
     */
    public FuelStation getFuelStation() {
        return fuelStation;
    }
    
    /**
     * Obtém a conexão com o SUMO
     */
    public SumoTraciConnection getSumo() {
        return sumo;
    }
    
    /**
     * Obtém a lista de motoristas
     */
    public List<Driver> getDrivers() {
        return drivers;
    }
    
    /**
     * Obtém o sistema de relatórios
     */
    public ReportingSystem getReportingSystem() {
        return reportingSystem;
    }
    
    /**
     * Verifica se a simulação está em execução
     */
    public boolean isSimulationRunning() {
        return simulationRunning.get();
    }
}