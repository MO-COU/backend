SHOW GLOBAL STATUS WHERE Variable_name IN (
  'Threads_connected', 'Threads_running', 'Connections',
  'Innodb_row_lock_time', 'Innodb_row_lock_waits',
  'Innodb_buffer_pool_reads', 'Innodb_buffer_pool_read_requests',
  'Created_tmp_disk_tables', 'Slow_queries'
);
