$env:PORT = "3000"
$env:DATABASE_URL = "postgres://rbs:rbs_dev_password@127.0.0.1:5433/rbs"
$env:PG_TEST_URL = "postgres://rbs:rbs_dev_password@127.0.0.1:5433/rbs_test"
Set-Location "d:\Ruta-Biker-Sync\backend"
node dist/index.js

