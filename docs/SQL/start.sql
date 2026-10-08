-- tb_sign 签到表
CREATE TABLE `tb_sign` (
                           `id` bigint unsigned NOT NULL COMMENT '主键',
                           `user_id` bigint unsigned NOT NULL COMMENT '用户id',
                           `year` year NOT NULL COMMENT '签到的年',
                           `month` tinyint NOT NULL COMMENT '签到的月',
                           `date` date NOT NULL COMMENT '签到的日期',
                           `is_backup` tinyint unsigned NOT NULL COMMENT '是否补签',
                           PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到表';

-- tb_seckill_voucher 秒杀券表
CREATE TABLE `tb_seckill_voucher` (
                                      `voucher_id` bigint unsigned NOT NULL COMMENT '关联的优惠券id',
                                      `stock` int NOT NULL COMMENT '库存',
                                      `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                      `begin_time` timestamp NOT NULL COMMENT '生效时间',
                                      `end_time` timestamp NOT NULL COMMENT '失效时间',
                                      `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                      PRIMARY KEY (`voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀券表';

-- tb_shop_type 店铺类型表
CREATE TABLE `tb_shop_type` (
                                `id` bigint unsigned NOT NULL COMMENT '主键',
                                `name` varchar(32) NOT NULL COMMENT '类型名称',
                                `icon` varchar(255) DEFAULT NULL COMMENT '图标',
                                `sort` int unsigned DEFAULT NULL COMMENT '排序',
                                `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺类型表';

-- tb_follow 关注表
CREATE TABLE `tb_follow` (
                             `id` bigint NOT NULL COMMENT '主键',
                             `user_id` bigint unsigned NOT NULL COMMENT '用户id',
                             `follow_user_id` bigint unsigned NOT NULL COMMENT '关联的用户id',
                             `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                             PRIMARY KEY (`id`),
                             KEY `idx_user_id` (`user_id`),
                             KEY `idx_follow_user_id` (`follow_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注表';


-- tb_voucher 优惠券表
CREATE TABLE `tb_voucher` (
                              `id` bigint unsigned NOT NULL COMMENT '主键',
                              `shop_id` bigint unsigned NOT NULL COMMENT '商铺id',
                              `title` varchar(255) NOT NULL COMMENT '代金券标题',
                              `sub_title` varchar(255) DEFAULT NULL COMMENT '副标题',
                              `rules` varchar(1024) DEFAULT NULL COMMENT '使用规则',
                              `pay_value` bigint unsigned NOT NULL COMMENT '支付金额（分）',
                              `actual_value` bigint NOT NULL COMMENT '抵扣金额（分）',
                              `threshold` decimal(10,2) DEFAULT NULL COMMENT '使用门槛（满X元可用），NULL=未录入',
                              `applicable_scope` varchar(512) DEFAULT NULL COMMENT '适用范围：店铺ID列表JSON，NULL=未录入',
                              `type` tinyint unsigned NOT NULL COMMENT '类型：0-普通券，1-秒杀券',
                              `status` tinyint unsigned NOT NULL COMMENT '状态：1-上架，2-下架，3-过期',
                              `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                              `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                              PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券表';

-- tb_shop 店铺表（SPEC-02 修复：原建表语句误用 blog 结构，此处按 Shop 实体重建）
CREATE TABLE `tb_shop` (
                           `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                           `name` varchar(128) NOT NULL COMMENT '商铺名称',
                           `type_id` bigint NOT NULL COMMENT '商铺类型id',
                           `images` varchar(1024) DEFAULT NULL COMMENT '商铺图片，多个图片以逗号隔开',
                           `area` varchar(128) DEFAULT NULL COMMENT '商圈，例如陆家嘴',
                           `address` varchar(255) DEFAULT NULL COMMENT '地址',
                           `x` double DEFAULT NULL COMMENT '经度',
                           `y` double DEFAULT NULL COMMENT '纬度',
                           `avg_price` bigint DEFAULT NULL COMMENT '均价，取整数',
                           `sold` int DEFAULT '0' COMMENT '销量',
                           `comments` int DEFAULT '0' COMMENT '评论数量',
                           `score` int DEFAULT '0' COMMENT '评分，1~5分，乘10保存，避免小数',
                           `open_hours` varchar(64) DEFAULT NULL COMMENT '营业时间，例如 10:00-22:00',
                           `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                           `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                           PRIMARY KEY (`id`),
                           KEY `idx_type_id` (`type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺表';

-- tb_blog 探店博客表（SPEC-02 修复：原全仓无建表语句）
CREATE TABLE `tb_blog` (
                           `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                           `shop_id` bigint NOT NULL COMMENT '商户id',
                           `user_id` bigint NOT NULL COMMENT '用户id',
                           `title` varchar(255) NOT NULL COMMENT '标题',
                           `images` varchar(1024) DEFAULT NULL COMMENT '探店的照片，最多9张，多张以逗号隔开',
                           `content` text COMMENT '探店的文字描述',
                           `liked` int DEFAULT '0' COMMENT '点赞数量',
                           `comments` int DEFAULT '0' COMMENT '评论数量',
                           `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                           `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                           PRIMARY KEY (`id`),
                           KEY `idx_shop_id` (`shop_id`),
                           KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店博客表';

-- tb_blog_comments 博客评论表（SPEC-02 修复：原表名 tb_shop_comments 与语义错位）
CREATE TABLE `tb_blog_comments` (
                                    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                    `user_id` bigint NOT NULL COMMENT '用户id',
                                    `blog_id` bigint NOT NULL COMMENT '博客id',
                                    `parent_id` bigint DEFAULT '0' COMMENT '父级ID（0为一级评论）',
                                    `answer_id` bigint DEFAULT NULL COMMENT '回答ID',
                                    `content` text NOT NULL COMMENT '回复内容',
                                    `liked` int DEFAULT '0' COMMENT '点赞数',
                                    `status` tinyint DEFAULT '0' COMMENT '状态：0-正常，1-被举报，2-禁止查看',
                                    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                    PRIMARY KEY (`id`),
                                    KEY `idx_blog_id` (`blog_id`),
                                    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='博客评论表';

-- tb_voucher_order 优惠券订单表
CREATE TABLE `tb_voucher_order` (
                                    `id` bigint NOT NULL COMMENT '主键',
                                    `user_id` bigint NOT NULL COMMENT '下单用户id',
                                    `voucher_id` bigint NOT NULL COMMENT '代金券id',
                                    `pay_type` tinyint DEFAULT NULL COMMENT '支付方式：1-余额，2-支付宝，3-微信',
                                    `status` tinyint DEFAULT '1' COMMENT '订单状态：1-未支付，2-已支付，3-已撤销，4-已取消，5-退款受理（终态，资金线下流转）',
                                    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
                                    `pay_time` timestamp DEFAULT NULL COMMENT '支付时间',
                                    `use_time` timestamp DEFAULT NULL COMMENT '核销时间',
                                    `refund_time` timestamp DEFAULT NULL COMMENT '退款时间',
                                    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                    PRIMARY KEY (`id`),
                                    KEY `idx_user_id` (`user_id`),
                                    KEY `idx_voucher_id` (`voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券订单表';

-- tb_user_info 用户信息表
-- SPEC-02 修复：主键列原写作 `id`，与 UserInfo 实体的 @TableId(value = "user_id") 不符
CREATE TABLE `tb_user_info` (
                                `user_id` bigint NOT NULL COMMENT '主键（用户id）',
                                `city` varchar(50) DEFAULT NULL COMMENT '城市名称',
                                `introduce` varchar(128) DEFAULT NULL COMMENT '个人介绍',
                                `fans` int DEFAULT '0' COMMENT '粉丝数量',
                                `followee` int DEFAULT '0' COMMENT '关注的人数量',
                                `gender` tinyint DEFAULT NULL COMMENT '性别：0-男，1-女',
                                `birthday` date DEFAULT NULL COMMENT '生日',
                                `credits` int DEFAULT '0' COMMENT '积分',
                                `level` tinyint DEFAULT '0' COMMENT '会员级别：0-未开通，1-9级',
                                `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';

-- tb_user 用户表（SPEC-02 修复：原全仓无建表语句）
CREATE TABLE `tb_user` (
                           `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                           `phone` varchar(20) NOT NULL COMMENT '手机号码',
                           `password` varchar(128) DEFAULT '' COMMENT '密码，加密存储',
                           `nick_name` varchar(32) DEFAULT NULL COMMENT '昵称，默认是随机字符',
                           `icon` varchar(255) DEFAULT '' COMMENT '用户头像',
                           `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                           `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                           PRIMARY KEY (`id`),
                           UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- tb_voucher_order 一人一单唯一约束（SPEC-02 G4：数据库层独立兜底，不再仅依赖 Redis Set）
-- 注意：存量库需先清理重复的 (user_id, voucher_id) 数据，否则本语句会因 Duplicate entry 失败
ALTER TABLE `tb_voucher_order`
    ADD UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`);

-- 遗留错位表处理（SPEC-02 §5.2）：旧脚本曾把 blog 评论结构建成 tb_shop_comments。
-- 本脚本已直接创建正确的 tb_blog_comments，故此处不无条件 RENAME/drop——
-- 无条件执行会在干净库上报 "table doesn't exist"，破坏"空库执行零错误"的验收（A9）。
-- 若确有历史库需保留该表数据，手工执行：
--   RENAME TABLE `tb_shop_comments` TO `tb_blog_comments_legacy`;