## 问题分析

1. **错误信息**：`Param check invalid:Param 'cluster' is illegal, illegal characters should not appear in the param.`
2. **错误来源**：Seata 1.6.1 客户端使用 Nacos Client 2.2.1 向 Nacos 注册时，cluster 参数包含非法字符
3. **问题定位**：从错误日志 `Redo subscriber operation REGISTER for SEATA_GROUP@@seata-server#default=default` 可以看出，cluster 参数的值是 `default=default`，包含了等号（=）字符，这是 Nacos 不允许的
4. **根本原因**：Seata 1.6.1 在构造 cluster 参数时，错误地将 `groupMapping` 的值与 `cluster` 拼接，导致生成了非法的 cluster 名称

## 解决方案

### 方案一：升级 Seata 版本（推荐）

Seata 1.7.0 及以上版本已经修复了这个问题，建议升级到最新稳定版。

**修改步骤**：
1. 修改父工程 `pom.xml` 中的 `seata.version` 为 `1.7.0` 或更高版本
2. 重新编译项目
3. 启动服务验证问题是否解决

### 方案二：修改 Seata 配置

通过修改 Seata 配置，确保 cluster 参数不包含非法字符。

**修改步骤**：
1. 修改所有使用 Seata 的服务的 `bootstrap.yaml` 文件
2. 调整 Seata 配置，确保 `vgroupMapping` 和 `cluster` 配置正确
3. 确保 cluster 参数只包含合法字符（字母、数字、下划线等）

### 方案三：降级 Nacos Client 版本

如果无法升级 Seata 版本，可以尝试降级 Nacos Client 版本，使用与 Seata 1.6.1 兼容的版本（如 Nacos Client 2.1.0）。

**修改步骤**：
1. 在父工程 `pom.xml` 中添加 Nacos Client 版本管理
2. 指定兼容的 Nacos Client 版本
3. 重新编译项目
4. 启动服务验证问题是否解决

## 实施计划

1. 首先尝试**方案一**，升级 Seata 版本到 1.7.0
2. 修改父工程 `pom.xml` 中的 Seata 版本
3. 重新编译所有模块
4. 启动 order-service 验证问题是否解决
5. 如果方案一不生效，尝试**方案二**，检查并修改 Seata 配置
6. 如果方案二仍不生效，尝试**方案三**，降级 Nacos Client 版本

## 预期效果

- 服务能够正常启动，不再抛出 cluster 参数非法字符的错误
- Seata 客户端能够成功向 Nacos 注册并发现服务
- 分布式事务功能正常工作