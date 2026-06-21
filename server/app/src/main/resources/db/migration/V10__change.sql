ALTER TABLE user_devices ADD CONSTRAINT user_devices_fcm_token_unique UNIQUE (fcm_token);
