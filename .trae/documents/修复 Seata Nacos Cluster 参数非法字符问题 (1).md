## 问题分析

错误信息显示：`Param check invalid:Param 'cluster' is illegal, illegal characters should not appear in the param.`

错误发生在Seata客户端向Nacos注册订阅时，具体是`REDO subscriber operation REGISTER for SEATA_GROUP@@seata-server#default=default failed`

从错误日志中的`SEATA_GROUP@@seata-server#default=default`可以看出，cluster参数被设置为`default=default`，其中的`=`字符是非法字符，导致Nacos拒绝了请求。

## 修复方案

1. **修改Seata配置**：调整`order-service`的`bootstrap.yaml`文件中的Seata配置，确保cluster参数不包含非法字符
2. **移除非法字符**：修改`service.grouplist`配置，将`default: localhost:8091`调整为正确的格式
3. **添加缺失配置**：添加`cluster`配置项，明确指定cluster名称

## 修复步骤

1. 编辑`order-service/src/main/resources/bootstrap.yaml`文件
2. 修改Seata配置部分，调整`service.grouplist`格式
3. 添加`cluster`配置项
4. 保存文件

## 预期结果

修复后，Seata客户端将能够成功向Nacos注册，不再出现"Param 'cluster' is illegal"错误，服务能够正常启动和运行。