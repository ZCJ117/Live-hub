CREATE TABLE undo_log (
                          id BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
                          branch_id BIGINT(20) NOT NULL COMMENT '分支事务ID',
                          xid VARCHAR(100) NOT NULL COMMENT '全局事务ID',
                          context VARCHAR(128) NOT NULL COMMENT '上下文，比如存放分支ID列表',
                          rollback_info LONGBLOB NOT NULL COMMENT '回滚信息，存储的是JSON格式的前镜像和后镜像',
                          log_status INT(11) NOT NULL COMMENT '日志状态：0-正常，1-已删除',
                          log_created DATETIME NOT NULL COMMENT '创建时间',
                          log_modified DATETIME NOT NULL COMMENT '修改时间',
                          PRIMARY KEY (id),
                          UNIQUE KEY ux_undo_log (xid, branch_id) COMMENT '唯一索引，防止重复插入'
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8 COMMENT='Seata AT模式回滚日志表';