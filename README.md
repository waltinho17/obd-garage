# OBD Garage

Painel digital para Android que lê dados do motor pelo OBD-II (adaptador ELM327 Bluetooth LE, testado com o Vgate iCar Pro) e mostra em dashes grandes, feitos para ler de longe com o celular parado no painel. Projeto pessoal, pensado para um VW Polo TSI (MQB), mas funciona com qualquer carro que fale OBD-II padrão.

**O app só lê.** Ele só envia comandos `AT*` ao adaptador e pedidos do modo 01 (e o VIN, modo 09). Nunca escreve nada no carro.

## O que tem

- **4 dashes:** Esporte, Turbo, Painel e Dados (gráfico dos últimos 60 s).
- **Pressão do turbo** relativa, com pico retido e RPM do pico; unidade em bar, kgf/cm², psi ou kPa.
- **Teor de etanol** (PID 52) no rodapé de todos os dashes: nome da mistura (E50) e uma barra fina com marcas em E30, E50 e E85. A ECU estima o teor depois do abastecimento, então ele leva alguns minutos de uso para assentar.
- **Log** de sessões em CSV com hora precisa, detecção de pulls, mín/máx e gráfico estilo dinamômetro.
- Detecta turbo x aspirado, nome do carro pelo VIN, temas claro/escuro, tamanho de texto e orientação ajustáveis.
- Leitura continua com a tela apagada (serviço em primeiro plano).
- Modo simulador (sem carro) para testar a interface.

## Compilar

Requisitos: JDK 17 e Android SDK (compileSdk 34). O JDK do Android Studio mais novo (25) quebra o Gradle 8.9; use o 17.

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
.\gradlew.bat testDebugUnitTest assembleDebug     # APK de teste
.\gradlew.bat assembleRelease                     # APK assinado (precisa do keystore.properties)
```

Saída: `app/build/outputs/apk/<debug|release>/`.

### Assinatura do release

Crie um `keystore.properties` na raiz (ele **não** vai para o git):

```properties
storeFile=C:/caminho/para/dashobd-release.jks
storePassword=...
keyAlias=dashobd
keyPassword=...
```

Guarde o `.jks` e as senhas em lugar seguro: sem a mesma chave não dá para atualizar o app já instalado.

## Instalar

Baixe o APK em **Releases**, copie para o celular e instale (permitir instalação de fontes desconhecidas). Ligue o Bluetooth e a localização (o Android exige para buscar dispositivos BLE).

## Segurança

- O pino 16 do OBD fica com 12 V sempre: desconecte o adaptador se o carro ficar parado por dias.
- Não opere o celular dirigindo.

## Estrutura

```
app/src/main/java/com/walter/dashobd/
  obd/   protocolo ELM327, sensores, agendador de leitura, simulador
  ble/   busca e conexão Bluetooth LE
  log/   gravação, CSV, pulls, estatísticas
  ui/    dashes, gauges, telas
```
