-- Backfill sender_role from the [Role] part of existing raw sender strings
UPDATE messages
SET sender_role = (REGEXP_MATCH(sender, '\[([^\]]+)\]'))[1]
WHERE message_source = 'LIBRUS'
  AND (sender_role IS NULL OR sender_role = '')
  AND sender ~ '\[[^\]]+\]';

-- Strip (loginName) and [Role] from existing sender strings
UPDATE messages
SET sender = TRIM(REGEXP_REPLACE(REGEXP_REPLACE(sender, '\s*\([^)]+\)', ''), '\s*\[[^\]]+\]', ''))
WHERE message_source = 'LIBRUS'
  AND sender ~ '(\([^)]+\)|\[[^\]]+\])';
