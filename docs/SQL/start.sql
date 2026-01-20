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
                              `type` tinyint unsigned NOT NULL COMMENT '类型：0-普通券，1-秒杀券',
                              `status` tinyint unsigned NOT NULL COMMENT '状态：1-上架，2-下架，3-过期',
                              `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                              `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                              PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券表';

-- tb_shop 店铺表
CREATE TABLE `tb_shop` (
                           `id` bigint NOT NULL COMMENT '主键',
                           `shop_id` bigint NOT NULL COMMENT '商户id',
                           `user_id` bigint NOT NULL COMMENT '用户id',
                           `title` varchar(255) NOT NULL COMMENT '标题',
                           `images` varchar(1024) DEFAULT NULL COMMENT '图片缩略图（多张用“|”隔开）',
                           `content` text COMMENT '评论内容',
                           `likes` int DEFAULT '0' COMMENT '点赞数量',
                           `comments` int DEFAULT '0' COMMENT '评论数量',
                           `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                           `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                           PRIMARY KEY (`id`),
                           KEY `idx_shop_id` (`shop_id`),
                           KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺表';

-- tb_shop_comments 店铺评论表
CREATE TABLE `tb_shop_comments` (
                                    `id` bigint NOT NULL COMMENT '主键',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺评论表';

-- tb_voucher_order 优惠券订单表
CREATE TABLE `tb_voucher_order` (
                                    `id` bigint NOT NULL COMMENT '主键',
                                    `user_id` bigint NOT NULL COMMENT '下单用户id',
                                    `voucher_id` bigint NOT NULL COMMENT '代金券id',
                                    `pay_type` tinyint DEFAULT NULL COMMENT '支付方式：1-余额，2-支付宝，3-微信',
                                    `status` tinyint DEFAULT '1' COMMENT '订单状态：1-未支付，2-已支付，3-已撤销，4-已取消，5-退款中，6-已退款',
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
CREATE TABLE `tb_user_info` (
                                `id` bigint NOT NULL COMMENT '主键',
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
                                PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';