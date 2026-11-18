$env:PORT = "3000"
$env:PG_TEST_URL = "postgres://rbs:rbs_dev_password@127.0.0.1:5433/rbs"
Set-Location "d:\Ruta-Biker-Sync\backend"
node dist/index.js

