@echo off
chcp 65001 > nul
echo ============================================
echo   TESTE DE PERFORMANCE DE MEMÓRIA
echo   JavaScript (Node.js) vs Java
echo ============================================
echo.

REM Criar diretórios necessários
if not exist "..\config" mkdir "..\config"
if not exist "..\reports\memory" mkdir "..\reports\memory"

REM Criar arquivo de configuração de exemplo se não existir
if not exist "..\config\memory-scenarios.json" (
    echo Criando arquivo de configuração de exemplo...
    (
        echo [
        echo   {
        echo     "id": "small",
        echo     "sizeMb": 10,
        echo     "iterations": 50
        echo   },
        echo   {
        echo     "id": "medium",
        echo     "sizeMb": 50,
        echo     "iterations": 30
        echo   },
        echo   {
        echo     "id": "large",
        echo     "sizeMb": 100,
        echo     "iterations": 10
        echo   }
        echo ]
    ) > "..\config\memory-scenarios.json"
    echo.
)

echo ============================================
echo EXECUTANDO TESTE EM NODE.JS
echo ============================================
echo.

node memoryTest.js --sizes=10,200,500,10000 --iterations=10 --output=nodejs-result.json

echo.
echo ============================================
echo COMPILANDO CÓDIGO JAVA
echo ============================================
echo.

javac MemoryTest.java
if %errorlevel% neq 0 (
    echo Erro ao compilar Java!
    pause
    exit /b 1
)

echo.
echo ============================================
echo EXECUTANDO TESTE EM JAVA
echo ============================================
echo.

java MemoryTest --sizes=10,200,500,10000 --iterations=10 --output=java-result.json

echo.
echo ============================================
echo TESTES CONCLUÍDOS
echo ============================================
echo.
echo Resultados salvos em:
echo - Node.js: ..\reports\memory\nodejs-result.json
echo - Java:    ..\reports\memory\java-result.json
echo.

REM Verificar se os arquivos existem e mostrar resumo
if exist "..\reports\memory\nodejs-result.json" (
    echo [✓] Resultado Node.js gerado com sucesso
) else (
    echo [✗] Resultado Node.js não encontrado
)

if exist "..\reports\memory\java-result.json" (
    echo [✓] Resultado Java gerado com sucesso
) else (
    echo [✗] Resultado Java não encontrado
)

echo.
echo Pressione qualquer tecla para sair...
pause > nul

