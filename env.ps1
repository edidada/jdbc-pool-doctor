<#
  用法（必须用「点源」执行，变量才会留在当前会话，并被随后启动的 java 子进程继承）：
      . .\env.ps1                          # 从 git 忽略的 env.local.ps1 读取口令
      . .\env.ps1 -Password '你的密码'       # 或直接传参

  只设置当前 PowerShell 进程的环境变量，不写入系统/用户级环境变量。
  口令不进版本库：本机口令放在 env.local.ps1（见 env.local.ps1.example），该文件被 .gitignore 忽略。
#>
param(
    [string]$DbHost   = '127.0.0.1',
    [int]   $DbPort   = 3306,
    [string]$Database = 'pool_doctor',
    [string]$User     = 'root',
    [string]$Password = ''
)

if (-not $Password) {
    $local = Join-Path $PSScriptRoot 'env.local.ps1'
    if (Test-Path $local) { . $local }
}
if (-not $Password) { $Password = $env:DB_PASSWORD }

if (-not $Password) {
    throw "未设置数据库口令：请复制 env.local.ps1.example 为 env.local.ps1 填入口令，或用 . .\env.ps1 -Password 'xxx'"
}

$env:DB_URL = "jdbc:mysql://${DbHost}:${DbPort}/${Database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&connectTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false"
$env:DB_USER = $User
$env:DB_PASSWORD = $Password

Write-Host "[env.ps1] DB_URL      = $env:DB_URL"
Write-Host "[env.ps1] DB_USER     = $env:DB_USER"
Write-Host "[env.ps1] DB_PASSWORD = ******（已隐藏，仅存在于当前进程）"
