## 问题分析

**报错信息**：`com.alibaba.nacos.api.exception.NacosException: Param check invalid:Param 'cluster' is illegal, illegal characters should not appear in the param.`

**失败的服务**：`SEATA_GROUP@@seata-server#default=default`

**根本原因**：
1. 项目使用 Seata 2.0.0 版本，与 Spring Cloud Alibaba 2022.0.0.0-RC2 兼容性存在问题
2. Seata 2.0.0 在向 Nacos 注册服务时，构建的服务名包含 `#` 和 `=` 等特殊字符
3. Nacos 对 cluster 参数进行严格验证，不允许包含这些特殊字符，导致参数校验失败

## 解决方案

将 Seata 版本从 2.0.0 降低到与 Spring Cloud Alibaba 2022.0.0.0-RC2 兼容的 1.6.1 版本。

## 实施步骤

1. **修改根目录 pom.xml**：将 Seata 版本从 2.0.0 改为 1.6.1
2. **检查并更新所有使用 Seata 的模块**：确保所有模块都使用兼容版本
3. **重新构建项目**：确保依赖关系正确更新
4. **验证修复效果**：启动项目验证 Nacos 服务注册是否成功

## 预期效果

- Seata 服务能够成功注册到 Nacos
- 分布式事务功能正常工作
- 项目启动不再出现 Nacos 注册失败的报错

## 注意事项

- Seata 1.6.1 版本与 Spring Cloud Alibaba 2022.0.0.0-RC2 兼容性良好
- 降低 Seata 版本不会影响现有功能的使用
- 如需使用 Seata 2.0.0 的新特性，建议等待 Spring Cloud Alibaba 官方发布兼容版本