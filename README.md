# SUMO Simulator - Projeto Completo

Este arquivo README contém informações importantes sobre o projeto SUMO Simulator.

## Estrutura do Projeto

O projeto implementa um sistema de simulação de tráfego urbano com os seguintes componentes:

- **Route**: Representa uma rota no sistema (não é uma Thread)
- **Account**: Gerencia contas bancárias (Thread)
- **AlphaBank**: Servidor bancário para transações (Thread)
- **Car**: Representa um veículo no sistema (Thread)
- **Driver**: Representa um motorista no sistema (Thread)
- **MobilityCompany**: Empresa de mobilidade que gerencia rotas e pagamentos (Thread)
- **FuelStation**: Posto de combustível para abastecimento (Thread)
- **Utilitários**: Classes para JSON, criptografia, relatórios Excel e gráficos

## Dependências Externas

Este projeto depende de bibliotecas externas que não estão disponíveis no repositório central do Maven:

- libsumo-1.18.0
- libtraci-1.18.0
- lisum-core
- lisum-gui
- TraaS

Consulte o arquivo `DEPENDENCIES_INSTALLATION.md` para instruções detalhadas sobre como instalar essas dependências.

## Compilação e Execução

1. Instale as dependências externas conforme as instruções em `DEPENDENCIES_INSTALLATION.md`
2. Compile o projeto com Maven:
   ```
   mvn clean install
   ```
3. Execute o projeto:
   ```
   java -jar target/sim-1.0-SNAPSHOT.jar
   ```

## Funcionalidades Implementadas

- Gerenciamento de rotas e veículos
- Sistema bancário para transações
- Lógica de abastecimento de combustível
- Pagamentos automatizados
- Geração de relatórios
- Comunicação segura com JSON e criptografia

## Observações

- O projeto foi desenvolvido conforme os requisitos especificados no documento PDF
- As 200 rotas foram extraídas e o arquivo map.rou.xml foi limpo
- Todas as classes (exceto Main e Route) são implementadas como Threads
- Placeholders foram criados para funcionalidades que dependem de bibliotecas externas

## Contato

Para mais informações, entre em contato com o professor ou consulte a documentação do SUMO em https://sumo.dlr.de/docs/
