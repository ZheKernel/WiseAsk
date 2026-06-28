-- Execute against the main Ragent database after cleaning evaluation orders.

DELETE FROM t_user
WHERE username = 'eval_admin'
   OR username LIKE 'eval_user\_%' ESCAPE '\';
