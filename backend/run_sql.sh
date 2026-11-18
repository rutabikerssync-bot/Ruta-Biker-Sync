PGPASSWORD=rbs_dev_password psql -h localhost -p 5433 -U rbs -d rbs -c "SELECT id, user_id, ride_id, expires_at FROM shared_medical_sheets;"











