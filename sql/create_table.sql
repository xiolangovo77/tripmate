-- TripMate 建表脚本
-- 数据库：MySQL 8.0.16+（使用 JSON 及表达式默认值）
-- 请先选择目标数据库，例如 USE tripmate;
-- 本脚本只创建表，不删除数据；IF NOT EXISTS 不会升级已有表结构。
-- 时间按应用与数据库约定的统一时区写入，金额单位为人民币元。
-- 不设置数据库外键，关联记录的存在性及删除一致性由 Java 维护。

SET NAMES utf8mb4;

-- 1. 用户：个人节奏、活动偏好直接保存在用户表。
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `userAccount` VARCHAR(64) NOT NULL COMMENT '账号',
    `password` VARCHAR(255) NOT NULL COMMENT '密码',
    `nickname` VARCHAR(64) NOT NULL COMMENT '昵称',
    `avatarUrl` VARCHAR(1024) DEFAULT NULL COMMENT '头像地址',
    `userRole` VARCHAR(256) DEFAULT 'user' NOT NULL COMMENT '用户角色：user/admin',
    `travelPace` ENUM('relaxed', 'balanced', 'packed') NOT NULL DEFAULT 'balanced' COMMENT '旅行节奏：悠闲、适中、充实',
    `favoriteActivities` JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '活动偏好数组：美食、自然、拍照、文化、购物',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_userAccount` (`userAccount`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 2. 旅行：不设置目标人数，实际人数按有效成员统计。
CREATE TABLE IF NOT EXISTS `trip` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '旅行ID',
    `ownerId` BIGINT UNSIGNED NOT NULL COMMENT '发起人用户ID',
    `title` VARCHAR(120) NOT NULL COMMENT '旅行名称',
    `destination` VARCHAR(120) NOT NULL COMMENT '目的地',
    `travelDays` SMALLINT UNSIGNED NOT NULL COMMENT '旅行天数',
    `status` ENUM('planning', 'confirmed', 'finished', 'cancelled') NOT NULL DEFAULT 'planning' COMMENT '旅行状态：筹备中、已确认、已结束、已取消',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_trip_owner_created` (`ownerId`, `createTime`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='旅行表';

-- 3. 旅行成员：加入时复制个人偏好，之后修改个人设置不影响已有旅行。
-- 发起人也必须有一条成员记录；退出后重新加入时更新原记录。
CREATE TABLE IF NOT EXISTS `trip_member` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '成员记录ID',
    `tripId` BIGINT UNSIGNED NOT NULL COMMENT '旅行ID',
    `userId` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `nickname` VARCHAR(64) NOT NULL COMMENT '昵称',
    `role` ENUM('owner', 'member') NOT NULL DEFAULT 'member' COMMENT '成员角色：发起人、普通成员',
    `budgetLimit` DECIMAL(12,2) NOT NULL COMMENT '本次旅行的个人预算上限',
    `travelPace` ENUM('relaxed', 'balanced', 'packed') NOT NULL COMMENT '本次旅行的节奏快照',
    `favoriteActivities` JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '本次旅行的活动偏好快照',
    `status` ENUM('joined', 'left') NOT NULL DEFAULT 'joined' COMMENT '成员状态：已加入、已退出',
    `joinedTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次加入时间',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trip_member_trip_user` (`tripId`, `userId`),
    KEY `idx_trip_member_user_status` (`userId`, `status`),
    KEY `idx_trip_member_trip_status` (`tripId`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='旅行成员表';

-- 4. 旅行邀请：同一邀请可供多人加入，过期状态由 expiresTime 判断。
CREATE TABLE IF NOT EXISTS `trip_invitation` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '邀请ID',
    `tripId` BIGINT UNSIGNED NOT NULL COMMENT '旅行ID',
    `inviterId` BIGINT UNSIGNED NOT NULL COMMENT '邀请人用户ID',
    `inviteCode` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '区分大小写的唯一随机邀请码',
    `expiresTime` DATETIME DEFAULT NULL COMMENT '过期时间，NULL表示不过期',
    `status` ENUM('active', 'revoked') NOT NULL DEFAULT 'active' COMMENT '邀请状态：有效、已撤销；使用时还需检查过期时间',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trip_invitation_code` (`inviteCode`),
    KEY `idx_trip_invitation_trip_status` (`tripId`, `status`),
    KEY `idx_trip_invitation_trip_code` (`tripId`, `inviteCode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='旅行邀请表';

-- 5. 行程：每个旅行只保留一份当前方案，AI 调整时更新该方案及明细。
CREATE TABLE IF NOT EXISTS `itinerary` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '行程ID',
    `tripId` BIGINT UNSIGNED NOT NULL COMMENT '旅行ID，一次旅行对应一份当前行程',
    `title` VARCHAR(120) NOT NULL COMMENT '行程标题',
    `summary` TEXT DEFAULT NULL COMMENT '行程概述及推荐理由',
    `estimatedCostPerPerson` DECIMAL(12,2) DEFAULT NULL COMMENT '每人预计费用，NULL表示尚未估算',
    `status` ENUM('draft', 'confirmed') NOT NULL DEFAULT 'draft' COMMENT '行程状态：草案、已确认',
    `confirmedBy` BIGINT UNSIGNED DEFAULT NULL COMMENT '确认人用户ID',
    `confirmedTime` DATETIME DEFAULT NULL COMMENT '确认时间',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_itinerary_trip` (`tripId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行程表';

-- 6. 行程明细：按第几天、当天顺序展示。
CREATE TABLE IF NOT EXISTS `itinerary_item` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '行程明细ID',
    `itineraryId` BIGINT UNSIGNED NOT NULL COMMENT '所属行程ID',
    `dayNumber` SMALLINT UNSIGNED NOT NULL COMMENT '第几天，从1开始',
    `sortOrder` SMALLINT UNSIGNED NOT NULL COMMENT '当天展示顺序，从1开始',
    `startTime` TIME NOT NULL COMMENT '开始时间',
    `endTime` TIME DEFAULT NULL COMMENT '结束时间，可为空',
    `activityName` VARCHAR(200) NOT NULL COMMENT '景点或活动名称',
    `activityType` ENUM('attraction', 'dining', 'transport', 'accommodation', 'free_time', 'other') NOT NULL COMMENT '活动类型：景点、餐饮、交通、住宿、自由活动、其他',
    `locationName` VARCHAR(200) DEFAULT NULL COMMENT '地点名称',
    `address` VARCHAR(500) DEFAULT NULL COMMENT '详细地址',
    `description` TEXT DEFAULT NULL COMMENT '活动安排说明',
    `estimatedCostPerPerson` DECIMAL(12,2) DEFAULT NULL COMMENT '本项人均预计费用，NULL表示待确认，0表示免费',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_itinerary_item_day_order` (`itineraryId`, `dayNumber`, `sortOrder`),
    KEY `idx_itinerary_id` (`itineraryId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行程明细表';

-- 7. 旅行消息：保存成员群聊和 AI 回复；普通聊天不调用 AI，用户 @AI 才触发回复。
-- AI 修改建议先保存为待处理，发起人统一选择建议并更新当前行程。
CREATE TABLE IF NOT EXISTS `trip_message` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `tripId` BIGINT UNSIGNED NOT NULL COMMENT '所属旅行ID',
    `senderId` BIGINT UNSIGNED DEFAULT NULL COMMENT '发送人用户ID，AI消息为空',
    `role` ENUM('user', 'assistant') NOT NULL COMMENT '消息角色：用户、AI',
    `content` MEDIUMTEXT NOT NULL COMMENT '消息内容，用户消息发送成功或AI回复完成后保存',
    `replyToId` BIGINT UNSIGNED DEFAULT NULL COMMENT '回复的消息ID，可为空，必须属于同一旅行',
    `recallStatus` ENUM('normal', 'recalled') NOT NULL DEFAULT 'normal' COMMENT '撤回状态：正常、已撤回',
    `changeStatus` ENUM('none', 'pending', 'applied', 'ignored') NOT NULL DEFAULT 'none' COMMENT '建议状态：无修改建议、待处理、已应用、已忽略；用户消息和普通AI回复为none',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trip_message_trip_id` (`tripId`, `id`),
    KEY `idx_trip_message_trip_created` (`tripId`, `createTime`),
    KEY `idx_trip_message_change_status` (`tripId`, `changeStatus`, `createTime`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='旅行消息表';