package io.sim;

import io.sim.reporting.ReportingSystem;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.tudresden.sumo.cmd.Vehicle;
import sim.traci4j.src.java.it.polito.appeal.traci.Repository;
import sim.traci4j.src.java.it.polito.appeal.traci.Edge;
import sim.traci4j.src.java.it.polito.appeal.traci.Lane;
import de.tudresden.sumo.objects.SumoColor;
import de.tudresden.sumo.objects.SumoPosition2D;
import it.polito.appeal.traci.SumoTraciConnection;
import java.awt.geom.Point2D;
import io.sim.utils.JsonUtil;
import io.sim.utils.GeoUtils;

/**
 * Classe que representa um carro no sistema de simulação.
 * Implementa Runnable para execução concorrente e atua como cliente para o servidor Company.
 */
public class Car extends sim.traci4j.src.java.it.polito.appeal.traci.Vehicle implements Runnable {

    private static final Logger logger = Logger.getLogger(Car.class.getName());
    private static final double REFUEL_THRESHOLD = 3.0; // Limite para abastecimento (3 litros)
    private static final double INITIAL_FUEL = 10.0; // Combustível inicial (10 litros)
    private static final long REFUEL_TIME = 120000; // Tempo de abastecimento (2 minutos em milissegundos)

    // Identificação do carro
    private String idCar;
    private SumoColor colorCar;
    private String driverID;
    private SumoTraciConnection sumo;

    // Controle de execução
    private boolean on_off;
    private long acquisitionRate;
    
    // Características do veículo
    private int fuelType;            // 1-diesel, 2-gasoline, 3-ethanol, 4-hybrid
    private int fuelPreferential;    // 1-diesel, 2-gasoline, 3-ethanol, 4-hybrid
    private double fuelPrice;        // price in liters
    private int personCapacity;      // the total number of persons that can ride in this vehicle
    private int personNumber;        // the total number of persons which are riding in this vehicle

    // Conexão com servidores
    private MobilityCompany companyServer; // Para enviar dados
    private FuelStation fuelStation;       // Para solicitar abastecimento
    private Socket companyConnection;
    private ObjectOutputStream companyOut;
    private ObjectInputStream companyIn;
    private boolean connected;
    
    // Sistema de relatórios
    //private ReportingSystem reportingSystem;
    
    // Tanque de combustível
    private double fuelTank;
    private boolean refueling;
    private long refuelingStartTime;
    
    // Dados de condução e relatórios
    private ArrayList<DrivingData> drivingReport_LOCAL;
    private SumoPosition2D lastPosition;
    private double totalOdometer;
    private double distanceSinceLastReport;

    // Criptografia dos dados de direção
    private EncriptaDecriptaDES companySessionEncryptor; // Para criptografia com MobilityCompany
    
    /**
     * Construtor principal do Car.
     * 
     * @param _on_off Estado inicial (ligado/desligado)
     * @param _idCar ID do carro
     * @param _colorCar Cor do carro
     * @param _driverID ID do motorista
     * @param _sumo Conexão com o SUMO
     * @param _acquisitionRate Taxa de aquisição de dados
     * @param _fuelType Tipo de combustível
     * @param _fuelPreferential Tipo de combustível preferencial
     * @param _fuelPrice Preço do combustível
     * @param _personCapacity Capacidade de pessoas
     * @param _personNumber Número de pessoas
     * @throws Exception Se ocorrer um erro na inicialização
     */
    public Car(DataInputStream _dis, DataOutputStream _dos, Repository<Edge> _repoEdge, Repository<Lane> _repoLane, boolean _on_off, String _idCar, SumoColor _colorCar, String _driverID, SumoTraciConnection _sumo, 
            long _acquisitionRate, int _fuelType, int _fuelPreferential, double _fuelPrice, 
            int _personCapacity, int _personNumber) throws Exception {
        
        // Chama o construtor da classe pai (Vehicle)
        super(_dis, _dos, _idCar, _repoEdge, _repoLane);
        
        this.on_off = _on_off;
        this.idCar = _idCar;
        this.colorCar = _colorCar;
        this.driverID = _driverID;
        this.sumo = _sumo;
        this.acquisitionRate = _acquisitionRate;
        
        if((_fuelType < 0) || (_fuelType > 4)) {
            this.fuelType = 4;
        } else {
            this.fuelType = _fuelType;
        }
        
        if((_fuelPreferential < 0) || (_fuelPreferential > 4)) {
            this.fuelPreferential = 4;
        } else {
            this.fuelPreferential = _fuelPreferential;
        }

        this.fuelPrice = _fuelPrice;
        this.personCapacity = _personCapacity;
        this.personNumber = _personNumber;
        this.drivingReport_LOCAL = new ArrayList<>();
        
        // Inicializa o tanque de combustível com 10 litros
        this.fuelTank = INITIAL_FUEL;
        this.refueling = false;
        
        // Inicializa contadores de distância
        this.totalOdometer = 0.0;
        this.distanceSinceLastReport = 0.0;
        this.lastPosition = null;
        
        logger.info("Car " + idCar + " criado com " + INITIAL_FUEL + "L de combustível.");
    }

    /**
     * Conecta ao servidor da Company.
     * 
     * @param host Host do servidor
     * @param port Porta do servidor
     * @throws IOException Se ocorrer um erro de conexão
     */
    public void connectToCompany(String host, int port) throws IOException {
        if (this.connected) {
            logger.info("Car " + idCar + ": Já conectado à Company.");
            return;
        }
        try {
            logger.info("Car " + idCar + " conectando ao servidor Company em " + host + ":" + port);
            this.companyConnection = new Socket(host, port);
            this.companyOut = new ObjectOutputStream(companyConnection.getOutputStream());
            this.companyOut.flush();
            this.companyIn = new ObjectInputStream(companyConnection.getInputStream());
            logger.info("Car " + idCar + ": Streams para Company criadas.");

            // 1. REGISTRO INICIAL (envia CarRegistration JSON)
            CarRegistration registration = new CarRegistration(this.idCar, this.driverID, "CAR_CLIENT");
            String registrationJson = JsonUtil.toJson(registration);
            companyOut.writeObject(registrationJson);
            companyOut.flush();
            logger.info("Car " + idCar + ": Mensagem de registro JSON enviada: " + registrationJson);

            // 2. RECEBER CHAVE PÚBLICA RSA DA COMPANY
            String companyRsaPublicKeyBase64 = (String) companyIn.readObject();
            logger.info("Car " + idCar + ": Chave pública RSA da Company recebida.");
            PublicKey companyRsaPublicKey = EncriptaDecriptaRSA.getPublicKeyFromBase64(companyRsaPublicKeyBase64);

            // 3. GERAR CHAVE DE SESSÃO DES, CRIPTOGRAFAR E ENVIAR
            EncriptaDecriptaDES desKeyGenerator = new EncriptaDecriptaDES();
            byte[] sessionDesKeyBytes = desKeyGenerator.getChaveDESBytes();
            this.companySessionEncryptor = new EncriptaDecriptaDES(sessionDesKeyBytes);

            byte[] encryptedSessionDesKey = EncriptaDecriptaRSA.criptografarComPublicKey(sessionDesKeyBytes, companyRsaPublicKey);
            companyOut.writeObject(encryptedSessionDesKey);
            companyOut.flush();
            logger.info("Car " + idCar + ": Chave de sessão DES criptografada enviada para Company.");

            // 4. RECEBER CONFIRMAÇÃO SEGURA
            String encryptedConfirmation = (String) companyIn.readObject();
            String confirmationMessage = this.companySessionEncryptor.descriptografar(encryptedConfirmation);

            if (!"REGISTRATION_SECURE_SUCCESS".equals(confirmationMessage)) {
                throw new IOException("Falha no handshake seguro com Company: " + confirmationMessage);
            }
            
            this.connected = true;
            logger.info("Car " + idCar + " conectado de forma SEGURA ao servidor Company.");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Car " + idCar + ": Erro ao conectar/negociar chave com Company: " + e.getMessage(), e);
            this.connected = false;
            this.companySessionEncryptor = null; // Garante que não tentará usar um encriptador inválido
            if (e instanceof IOException) throw (IOException)e;
            else throw new IOException("Erro na negociação de chave com Company: " + e.getMessage(), e);
        }
    }
    
    /**
     * Define a Fuel Station para este carro.
     * 
     * @param fuelStation Instância da Fuel Station
     */
    public void setFuelStation(FuelStation fuelStation) {
        this.fuelStation = fuelStation;
    }
    
	/**
     * Define o sistema de relatórios para este carro.
     * 
     * @param reportingSystem Sistema de relatórios a ser utilizado
     */
    // public void setReportingSystem(ReportingSystem reportingSystem) {
    //     this.reportingSystem = reportingSystem;
    // }
	
    /**
     * Define o servidor Company para este carro.
     * 
     * @param companyServer Instância do servidor Company
     */
    public void setCompanyServer(MobilityCompany companyServer) {
        this.companyServer = companyServer;
    }

    /**
     * Método principal da thread.
     * Gerencia a execução do carro, atualizando sensores e enviando dados.
     */
    
    @Override
    public void run() {
        // A conexão com a Company agora deve ser estabelecida ANTES de iniciar a thread do Car
        if (!this.connected || this.companySessionEncryptor == null) {
            logger.severe("Car " + idCar + " iniciando run() SEM conexão segura com a Company. Encerrando thread do carro.");
            this.on_off = false; // Garante que o loop não execute
        }

        while (this.on_off) {
            try {
                if (refueling) {
                    handleRefueling();
                } else {
                    Thread.sleep(this.acquisitionRate);
                    
                    DrivingData newDataPoint = this.atualizaSensoresEObtemDados(); 
                    
                    if (newDataPoint != null) { // Se for nulo, o erro já foi logado e on_off possivelmente false
                        //logger.info("CAR_RUN (" + idCar + "): newDataPoint GERADO, tentando enviar.");
                        sendSingleDrivingDataToCompany(newDataPoint);
                    } else {
                        // Se newDataPoint é null, atualizaSensoresEObtemDados já deve ter lidado com on_off
                        // logger.warning("CAR_RUN (" + idCar + "): newDataPoint é NULL. Verifique logs anteriores.");
                        if (!this.on_off) { // Confirma se o carro foi desligado
                             logger.info("CAR_RUN (" + idCar + "): Carro foi desligado devido a erro anterior na obtenção de dados.");
                        }
                    }
                }
            } catch (InterruptedException e) {
                logger.warning("Car " + idCar + " thread interrompida: " + e.getMessage());
                Thread.currentThread().interrupt();
                this.on_off = false;
            } catch (Exception e) { // Captura genérica para erros inesperados no loop principal do Car
                logger.log(Level.SEVERE, "Erro inesperado na execução do Car " + idCar + ": " + e.getMessage(), e);
                this.on_off = false; // Desliga o carro em caso de erro grave
            }
        }
        cleanup();
    }

    /**
     * Atualiza os sensores do carro e coleta dados de condução.
     */
    public DrivingData atualizaSensoresEObtemDados() {
        String logPrefix = "CAR_SENSOR (" + this.idCar + "): ";
        DrivingData report = null;

        if (this.sumo == null || this.sumo.isClosed()) {
            logger.warning(logPrefix + "Conexão SUMO fechada ou nula. Desligando carro.");
            this.on_off = false;
            return null;
        }

        try {
            // Tenta uma operação básica para verificar se o veículo é conhecido
            // Se esta falhar com "not known", as outras também falharão.
            sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getSpeed(this.idCar)); // Teste de "conhecimento"

            SumoPosition2D sumoPosition2D = (SumoPosition2D) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getPosition(this.idCar));
            //Point2D currentAwtPosition = getPosition(); // Este método é da superclasse TraciObject -> Vehicle

            if (sumoPosition2D == null) { // Pode acontecer se o veículo foi removido entre os comandos
                 logger.warning(logPrefix + "Posição nula do SUMO (veículo pode ter sido removido inesperadamente). Desligando carro.");
                 this.on_off = false; 
                 return null;
            }
            
            // Verificação de NaN ou Infinito
            if (Double.isNaN(sumoPosition2D.x) || Double.isNaN(sumoPosition2D.y) ||
                Double.isInfinite(sumoPosition2D.x) || Double.isInfinite(sumoPosition2D.y)) {
                logger.warning(logPrefix + "Posição SUMO (sumoPosition2D) contém NaN ou Infinito. x=" + sumoPosition2D.x + ", y=" + sumoPosition2D.y + ". Desligando carro.");
                this.on_off = false;
                return null;
            }

            double distanceDelta = 0.0;
            if (lastPosition != null) {
                distanceDelta = GeoUtils.calculateEuclideanDistance(sumoPosition2D.x, sumoPosition2D.y, lastPosition.x, lastPosition.y);
                this.totalOdometer += distanceDelta;
            }
            this.lastPosition = sumoPosition2D;

            String roadID_fromSUMO = (String) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getRoadID(this.idCar));
            String routeID_fromSUMO = (String) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getRouteID(this.idCar));
            double speed_fromSUMO = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getSpeed(this.idCar));
            double odometerFromSUMOForRoute = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getDistance(this.idCar));
            double fuelConsumptionSim_fromSUMO = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getFuelConsumption(this.idCar));
            double co2Emission_fromSUMO = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getCO2Emission(this.idCar));
            double hcEmission_fromSUMO = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getHCEmission(this.idCar));

            double[] geoCoords = GeoUtils.convertToGeo(sumoPosition2D.x, sumoPosition2D.y);

        if (geoCoords == null || Double.isNaN(geoCoords[0]) || Double.isNaN(geoCoords[1])) {
            logger.warning(logPrefix + "GeoUtils.convertToGeo retornou inválido (null, NaN) para x=" + sumoPosition2D.x + ", y=" + sumoPosition2D.y + ". Desligando carro.");
            this.on_off = false;
            return null;
        }

            double fuelConsumedLiters = convertFuelConsumptionToLiters(fuelConsumptionSim_fromSUMO, this.acquisitionRate);
            updateFuelTank(fuelConsumedLiters);
            
            report = new DrivingData(
                    this.idCar, this.driverID, System.currentTimeMillis(), 
                    sumoPosition2D.x, sumoPosition2D.y, 
                    geoCoords,
                    roadID_fromSUMO, routeID_fromSUMO, speed_fromSUMO,
                    odometerFromSUMOForRoute, // Usando seu odômetro calculado
                    fuelConsumptionSim_fromSUMO, // Valor bruto do SUMO
                    calculateAverageFuelConsumption(), // Seu cálculo de média
                    this.fuelType, this.fuelPrice, 
                    co2Emission_fromSUMO, hcEmission_fromSUMO, 
                    this.personCapacity, this.personNumber
            );
            
            synchronized(drivingReport_LOCAL) {
                this.drivingReport_LOCAL.add(report);
            }

            if (this.fuelTank <= REFUEL_THRESHOLD && !refueling) {
                startRefueling();
            }
            // Não é necessário chamar setSpeedMode aqui em cada passo se não houver mudança.
            // if (!refueling && !this.sumo.isClosed()) {
            //     this.sumo.do_job_set(de.tudresden.sumo.cmd.Vehicle.setSpeedMode(this.idCar, 32)); 
            // }

        } catch (it.polito.appeal.traci.TraCIException e) {
            String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (errorMsg.contains(this.idCar.toLowerCase() + "' is not known") || errorMsg.contains("does not exist")) {
                logger.warning(logPrefix + "Veículo " + this.idCar + " não (ou não mais) conhecido pelo SUMO. Desligando carro. Erro: " + e.getMessage());
            } else {
                logger.log(Level.WARNING, logPrefix + "Erro TraCI ao atualizar sensores: " + e.getMessage(), e);
            }
            this.on_off = false; // Importante para parar o loop do carro
            return null;
        } catch (Exception e) { // Captura outras exceções
            logger.log(Level.SEVERE, logPrefix + "Erro GERAL INESPERADO ao atualizar sensores: " + e.getMessage(), e);
            this.on_off = false; // Desliga o carro em caso de erro grave
            return null; 
        }
        // logger.info(logPrefix + "DrivingData criado para timestamp: " + (report != null ? report.getTimeStamp() : "NULL"));
        return report;
    }

    /**
     * Inicia a execução do carro como uma thread.
     */
    public void start() {
        Thread t = new Thread(this);
        t.start();
    }

        /**
     * Envia dados de condução para o servidor Company.
     */
    private void sendSingleDrivingDataToCompany(DrivingData dataPoint) {
        String logPrefix = "CAR_SEND (" + idCar + "): ";
        if (dataPoint == null) {
            logger.warning(logPrefix + "dataPoint é nulo, não pode enviar.");
            return;
        }
        if (!this.connected) {
            logger.warning(logPrefix + "NÃO CONECTADO à Company. Dados NÃO ENVIADOS para timestamp: " + dataPoint.getTimeStamp());
            return;
        }
        if (this.companySessionEncryptor == null) {
             logger.severe(logPrefix + "ERRO CRÍTICO - companySessionEncryptor NULO ao tentar enviar dados. Handshake com Company falhou? Dados NÃO ENVIADOS para timestamp: " + dataPoint.getTimeStamp());
             this.connected = false; // Marcar para possível reconexão
             return;
        }

        try {
            String reportJson = JsonUtil.toJson(dataPoint);
            // logger.fine(logPrefix + "JSON para Company: " + reportJson);
            String encryptedReportJson = this.companySessionEncryptor.criptografar(reportJson);
            // logger.fine(logPrefix + "JSON Criptografado (início): " + encryptedReportJson.substring(0, Math.min(encryptedReportJson.length(), 30)) + "...");
            
            if (companyOut == null) {
                logger.severe(logPrefix + "companyOut é NULO. Impossível enviar dados.");
                cleanup(); // Tenta limpar e força reconexão
                return;
            }

            companyOut.writeObject(encryptedReportJson);
            companyOut.flush();
            logger.info(logPrefix + "DrivingData CRIPTOGRAFADO enviado para Company (Timestamp: " + dataPoint.getTimeStamp() + ")");
        } catch (java.net.SocketException se) {
            logger.log(Level.SEVERE, logPrefix + "SocketException ao enviar DrivingData: " + se.getMessage() + ". Conexão provavelmente perdida.", se);
            logger.info("CAR_SEND (" + idCar + "): Enviando TS: " + dataPoint.getTimeStamp() + ". Encryptor OK? " + (this.companySessionEncryptor != null));
            cleanup();
        } catch (Exception e) {
            logger.log(Level.SEVERE, logPrefix + "FALHA ao enviar DrivingData criptografado: " + e.getMessage(), e);
            cleanup(); 
        }
    }
    
    /**
     * Inicia o processo de abastecimento.
     */
    private void startRefueling() {
        try {
            if (fuelStation == null) {
                logger.warning("Car " + idCar + " precisa abastecer, mas não há Fuel Station configurada");
                return;
            }
            
            logger.info("Car " + idCar + " iniciando abastecimento. Combustível atual: " + fuelTank + " litros");
            
            // Para o veículo
            sumo.do_job_set(Vehicle.setSpeed(this.idCar, 0));
            
            // Marca como abastecendo
            this.refueling = true;
            this.refuelingStartTime = System.currentTimeMillis();
            
            // Solicita abastecimento à Fuel Station
            // Nota: A implementação completa dependeria da interface da FuelStation
            // Por enquanto, apenas simulamos o abastecimento
            double amountToRefuel = INITIAL_FUEL - fuelTank;
            logger.info("Car " + idCar + " solicitando abastecimento de " + amountToRefuel + " litros");
            
            // Em uma implementação real, chamaria um método da FuelStation
            // fuelStation.requestRefuel(this, amountToRefuel);
        } catch (Exception e) {
            logger.severe("Erro ao iniciar abastecimento do carro " + idCar + ": " + e.getMessage());
            this.refueling = false;
        }
    }
    
    /**
     * Gerencia o processo de abastecimento em andamento.
     */
    private void handleRefueling() throws InterruptedException {
        // Verifica se o tempo de abastecimento já passou (2 minutos)
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - refuelingStartTime;
        
        if (elapsedTime >= REFUEL_TIME) {
            // Abastecimento concluído
            completeRefueling();
        } else {
            // Ainda abastecendo, mantém o veículo parado
            try {
                sumo.do_job_set(Vehicle.setSpeed(this.idCar, 0));
            } catch (Exception e) {
                logger.warning("Erro ao manter veículo parado durante abastecimento: " + e.getMessage());
            }
            
            // Aguarda um pouco antes de verificar novamente
            Thread.sleep(1000);
        }
    }
    
    /**
     * Finaliza o processo de abastecimento.
     */
    private void completeRefueling() {
        try {
            // Simula o abastecimento completo
            this.fuelTank = INITIAL_FUEL;
            this.refueling = false;
            
            logger.info("Car " + idCar + " concluiu o abastecimento. Combustível atual: " + fuelTank + " litros");
            
            // Em uma implementação real, processaria o pagamento aqui
            // Isso seria feito pelo Driver através do BotPayment
        } catch (Exception e) {
            logger.severe("Erro ao completar abastecimento do carro " + idCar + ": " + e.getMessage());
        }
    }
    
    /**
     * Atualiza o nível do tanque de combustível com base no consumo.
     * 
     * @param consumedLiters Quantidade de litros consumidos
     */
    private void updateFuelTank(double consumedLiters) {
        // Atualiza o nível do tanque
        this.fuelTank -= consumedLiters;
        
        // Garante que não fique negativo
        if (this.fuelTank < 0) {
            this.fuelTank = 0;
        }
    }
    
    /**
     * Converte o consumo de combustível de mg/s para litros.
     * 
     * @param fuelConsumptionMgPerS Consumo em mg/s
     * @param timeMs Tempo em milissegundos
     * @return Consumo em litros
     */
    private double convertFuelConsumptionToLiters(double fuelConsumptionMgPerS, long timeMs) {
        // Converte mg/s para g/s
        double fuelConsumptionGPerS = fuelConsumptionMgPerS / 1000.0;
        
        // Converte g/s para g no período
        double fuelConsumptionG = fuelConsumptionGPerS * (timeMs / 1000.0);
        
        // Converte g para litros (densidade aproximada da gasolina: 750g/L)
        double densityGPerL = 750.0;
        return fuelConsumptionG / densityGPerL;
    }
    
    /**
     * Calcula o consumo médio de combustível.
     * 
     * @return Consumo médio em km/L
     */
    private double calculateAverageFuelConsumption() {
        if (drivingReport_LOCAL.isEmpty()) {
            return 0.0;
        }
        
        // Calcula a média dos últimos relatórios
        double totalConsumption = 0.0;
        int count = 0;
        int maxSamples = Math.min(10, drivingReport_LOCAL.size());
        
        for (int i = drivingReport_LOCAL.size() - 1; i >= drivingReport_LOCAL.size() - maxSamples; i--) {
            totalConsumption += drivingReport_LOCAL.get(i).getFuelConsumption();
            count++;
        }
        
        return (count > 0) ? totalConsumption / count : 0.0;
    }
    
    /**
     * Calcula a distância entre duas posições.
     * 
     * @param pos1 Primeira posição
     * @param pos2 Segunda posição
     * @return Distância em metros
     */
    public double calculateDistance(SumoPosition2D pos1, SumoPosition2D pos2) {
        // Cálculo da distância euclidiana
        double dx = pos2.x - pos1.x;
        double dy = pos2.y - pos1.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Tenta reconectar ao servidor Company.
     * 
     * @throws Exception Se ocorrer um erro na reconexão
     */
    private void reconnectToCompany() throws Exception {
        logger.info("Car " + idCar + " tentando reconectar ao servidor Company...");
        
        // Fecha conexão atual se existir
        if (companyConnection != null && !companyConnection.isClosed()) {
            try {
                companyConnection.close();
            } catch (IOException e) {
                // Ignora erros ao fechar
            }
        }
        
        this.connected = false;
        
        // Tenta reconectar
        String host = companyConnection.getInetAddress().getHostName();
        int port = companyConnection.getPort();
        
        // Reconecta
        connectToCompany(host, port);
    }
    
    /**
     * Limpa recursos ao encerrar o carro.
     */
    private void cleanup() {
        // Fecha conexão com a Company
        try {
            if (companyOut != null) companyOut.close();
            if (companyIn != null) companyIn.close();
            if (companyConnection != null && !companyConnection.isClosed()) {
                companyConnection.close();
            }
        } catch (IOException e) {
            logger.warning("Erro ao fechar conexões: " + e.getMessage());
        }
        
        logger.info("Car " + idCar + " finalizado");
    }
    
    /**
     * Classe interna para representar uma solicitação de registro.
     */
    public static class CarRegistration {
        private String carId;
        private String driverId;
        private String clientType;
        
        public CarRegistration() {
        }

        public CarRegistration(String carId, String driverId, String clientType) {
            this.carId = carId;
            this.driverId = driverId;
            this.clientType = clientType;
        }
        
        // Getters necessários para serialização JSON
        public String getCarId() {
            return carId;
        }
        
        public String getDriverId() {
            return driverId;
        }
        
        public String getClientType() {
            return clientType;
        }

        public void setCarId(String carId) { this.carId = carId; }
        public void setDriverId(String driverId) { this.driverId = driverId; }
        public void setClientType(String clientType) { this.clientType = clientType; }
    }
    
    // Getters e Setters
    
    public boolean isOn_off() {
        return this.on_off;
    }

    public void setOn_off(boolean _on_off) {
        this.on_off = _on_off;
    }

    public long getAcquisitionRate() {
        return this.acquisitionRate;
    }

    public void setAcquisitionRate(long _acquisitionRate) {
        this.acquisitionRate = _acquisitionRate;
    }

    public String getIdCar() {
        return this.idCar;
    }

    public void setIdCar(String idCar) {
        this.idCar = idCar;
    }

    public SumoColor getColorCar() {
        return colorCar;
    }

    public void setColorCar(SumoColor colorCar) {
        this.colorCar = colorCar;
    }

    public String getDriverID() {
        return driverID;
    }

    public void setDriverID(String driverID) {
        this.driverID = driverID;
    }

    public SumoTraciConnection getSumo() {
        return this.sumo;
    }

    public void setSumo(SumoTraciConnection sumo) {
        this.sumo = sumo;
    }

    public int getFuelType() {
        return this.fuelType;
    }

    public void setFuelType(int _fuelType) {
        if((_fuelType < 0) || (_fuelType > 4)) {
            this.fuelType = 4;
        } else {
            this.fuelType = _fuelType;
        }
    }

    public double getFuelPrice() {
        return this.fuelPrice;
    }

    public void setFuelPrice(double _fuelPrice) {
        this.fuelPrice = _fuelPrice;
    }

    public SumoColor getcolorCar() {
        return this.colorCar;
    }

    public int getFuelPreferential() {
        return this.fuelPreferential;
    }

    public void setFuelPreferential(int _fuelPreferential) {
        if((_fuelPreferential < 0) || (_fuelPreferential > 4)) {
            this.fuelPreferential = 4;
        } else {
            this.fuelPreferential = _fuelPreferential;
        }
    }

    public int getPersonCapacity() {
        return this.personCapacity;
    }

    public int getPersonNumber() {
        return this.personNumber;
    }

    public void setPersonCapacity(int personCapacity) {
        this.personCapacity = personCapacity;
    }

    public void setPersonNumber(int personNumber) {
        this.personNumber = personNumber;
    }

    public ArrayList<DrivingData> getDrivingRepport() { // Mantém para o Driver pegar no final da rota
        return new ArrayList<>(this.drivingReport_LOCAL); // Retorna uma cópia
    }

    public void clearDrivingRepport() { // Usado pelo Driver após processar os dados de uma rota
        this.drivingReport_LOCAL.clear();
        this.lastPosition = null; 
        // this.distanceSinceLastReport = 0.0; // Se você tinha esse campo, resete-o.
        logger.info("Car " + idCar + ": Relatório de condução local (drivingReport_LOCAL) limpo.");
    }

    public void setDrivingRepport(ArrayList<DrivingData> drivingRepport) {
        this.drivingReport_LOCAL = drivingRepport;
    }
    
    public double getFuelTank() {
        return fuelTank;
    }
    
	public void setFuelTank(double fuelTank){
		this.fuelTank = fuelTank;
	}

    public boolean isRefueling() {
        return refueling;
    }
    
	public void setRefueling(boolean refueling){
		this.refueling = refueling;
	}

    public double getTotalDistance() {
        return totalOdometer;
    }
    
    public boolean isConnected() {
        return connected;
    }
}
