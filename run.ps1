<#
  构建并启动复现应用（在 PowerShell 中运行）。
      .\run.ps1                              # 默认：错误配置，复现问题
      .\run.ps1 -ActiveProfile hikari        # 方案二：显式使用 HikariCP
      .\run.ps1 -ActiveProfile druid-fixed   # 方案一：显式使用 Druid 并修正参数
      .\run.ps1 -SkipBuild                   # 跳过 Maven 构建，直接启动已有 jar
      .\run.ps1 -MysqlExe 'D:\path\to\mysql.exe'
#>
param(
    [string]$ActiveProfile = '',
    [int]   $Port = 18080,
    [switch]$SkipBuild,
    [string]$MysqlExe = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# 1) 环境变量：点源，使变量进入当前进程并被子 java 进程继承
. (Join-Path $root 'env.ps1')

# 2) 定位 mysql.exe，创建演示库 pool_doctor
if (-not $MysqlExe) {
    $found = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($found) {
        $MysqlExe = $found.Source
    } else {
        $svc = Get-CimInstance Win32_Service -Filter "Name='MySQL'" -ErrorAction SilentlyContinue
        if ($svc) {
            $binDir = Split-Path (($svc.PathName -replace '"', '') -replace '\s+MySQL.*$', '')
            $candidate = Join-Path $binDir 'mysql.exe'
            if (Test-Path $candidate) { $MysqlExe = $candidate }
        }
    }
}

if ($MysqlExe -and (Test-Path $MysqlExe)) {
    Write-Host "[run.ps1] 创建数据库 pool_doctor（若不存在）"
    $env:MYSQL_PWD = $env:DB_PASSWORD   # 口令走环境变量，不出现在命令行
    & $MysqlExe -h 127.0.0.1 -P 3306 -u $env:DB_USER -e "CREATE DATABASE IF NOT EXISTS pool_doctor DEFAULT CHARACTER SET utf8mb4;"
    $createExit = $LASTEXITCODE
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    if ($createExit -ne 0) { throw "创建数据库失败，请检查 MySQL 连接与口令" }
} else {
    Write-Warning "[run.ps1] 未找到 mysql.exe：请用 -MysqlExe 指定，或手工创建 pool_doctor 库"
}

# 3) 构建
if (-not $SkipBuild) {
    Write-Host "[run.ps1] mvn clean package -DskipTests"
    & mvn -q -f (Join-Path $root 'pom.xml') clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }
}

# 4) 启动
$jar = Join-Path $root 'repro-web\target\repro-web-1.0.0.jar'
if (-not (Test-Path $jar)) { throw "找不到 $jar，请先构建" }

$javaArgs = @('-jar', $jar, "--server.port=$Port")
if ($ActiveProfile) { $javaArgs += "--spring.profiles.active=$ActiveProfile" }
Write-Host "[run.ps1] java $($javaArgs -join ' ')"
& java @javaArgs
