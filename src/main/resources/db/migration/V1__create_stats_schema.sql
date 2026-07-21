CREATE TABLE IF NOT EXISTS df_game_account (
    id VARCHAR(64) PRIMARY KEY,
    owner_user_id VARCHAR(64) NOT NULL,
    game_player_id VARCHAR(128) NOT NULL,
    player_name VARCHAR(255) NOT NULL,
    player_icon VARCHAR(1024),
    level_value INTEGER DEFAULT 0,
    area_value INTEGER DEFAULT 36,
    is_active BOOLEAN DEFAULT FALSE,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    CONSTRAINT uq_df_account_owner_player UNIQUE (owner_user_id, game_player_id)
);

CREATE INDEX IF NOT EXISTS idx_df_account_owner ON df_game_account(owner_user_id);

CREATE TABLE IF NOT EXISTS df_battle (
    id VARCHAR(64) PRIMARY KEY,
    owner_user_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    room_id VARCHAR(160) NOT NULL,
    map_id VARCHAR(64),
    map_name VARCHAR(255),
    event_time VARCHAR(40),
    start_time VARCHAR(40),
    duration_seconds INTEGER DEFAULT 0,
    game_result INTEGER DEFAULT 0,
    game_rule INTEGER DEFAULT 0,
    role_id VARCHAR(64),
    role_name VARCHAR(255),
    kill_count INTEGER DEFAULT 0,
    player_kill_count INTEGER DEFAULT 0,
    assist_count INTEGER DEFAULT 0,
    rescue_count INTEGER DEFAULT 0,
    collection_value BIGINT DEFAULT 0,
    gained_value BIGINT DEFAULT 0,
    profit_loss BIGINT DEFAULT 0,
    equipment_value BIGINT DEFAULT 0,
    ranked BOOLEAN DEFAULT FALSE,
    left_early BOOLEAN DEFAULT FALSE,
    blue_box BOOLEAN DEFAULT FALSE,
    queue_name VARCHAR(40),
    fetched_at VARCHAR(40) NOT NULL,
    CONSTRAINT fk_df_battle_account FOREIGN KEY (account_id) REFERENCES df_game_account(id) ON DELETE CASCADE,
    CONSTRAINT uq_df_battle_owner_account_room UNIQUE (owner_user_id, account_id, room_id)
);

CREATE INDEX IF NOT EXISTS idx_df_battle_owner_account_time ON df_battle(owner_user_id, account_id, event_time);
CREATE INDEX IF NOT EXISTS idx_df_battle_owner_room ON df_battle(owner_user_id, room_id);

CREATE TABLE IF NOT EXISTS df_battle_player (
    id VARCHAR(64) PRIMARY KEY,
    owner_user_id VARCHAR(64) NOT NULL,
    battle_id VARCHAR(64) NOT NULL,
    game_player_id VARCHAR(128),
    player_name VARCHAR(255),
    player_icon VARCHAR(1024),
    team_id INTEGER,
    role_id VARCHAR(64),
    role_name VARCHAR(255),
    kill_count INTEGER DEFAULT 0,
    player_kill_count INTEGER DEFAULT 0,
    assist_count INTEGER DEFAULT 0,
    rescue_count INTEGER DEFAULT 0,
    damage BIGINT DEFAULT 0,
    gained_value BIGINT DEFAULT 0,
    profit_loss BIGINT DEFAULT 0,
    is_self BOOLEAN DEFAULT FALSE,
    CONSTRAINT fk_df_player_battle FOREIGN KEY (battle_id) REFERENCES df_battle(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_df_player_owner_battle ON df_battle_player(owner_user_id, battle_id);

CREATE TABLE IF NOT EXISTS df_battle_item (
    id VARCHAR(64) PRIMARY KEY,
    owner_user_id VARCHAR(64) NOT NULL,
    battle_id VARCHAR(64) NOT NULL,
    game_player_id VARCHAR(128),
    item_id VARCHAR(128),
    item_name VARCHAR(255),
    picture_url VARCHAR(1024),
    grade INTEGER DEFAULT 0,
    quantity INTEGER DEFAULT 1,
    unit_price BIGINT DEFAULT 0,
    CONSTRAINT fk_df_item_battle FOREIGN KEY (battle_id) REFERENCES df_battle(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_df_item_owner_battle ON df_battle_item(owner_user_id, battle_id);
