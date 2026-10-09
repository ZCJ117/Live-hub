package com.hmdp.shop.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.lang.TypeReference;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.cache.MultiLevelCache;
import com.hmdp.cache.MultiLevelCacheFactory;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.shop.mapper.ShopTypeMapper;
import com.hmdp.shop.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    /** 分类列表的值类型：泛型 List，供二级缓存反序列化用 */
    private static final Type SHOP_TYPE_LIST_TYPE = new TypeReference<List<ShopType>>() {}.getType();

    private final MultiLevelCache<List<ShopType>> cache;

    public ShopTypeServiceImpl(MultiLevelCacheFactory cacheFactory) {
        this.cache = cacheFactory.create("shopTypeList", SHOP_TYPE_LIST_TYPE);
    }

    @Override
    public Result queryList() {
        log.info("开始查询商铺分类列表");

        List<ShopType> typeList = cache.get(SHOP_LIST_KEY, () -> {
            log.info("本地缓存与 Redis 均未命中，开始查询数据库");
            List<ShopType> fromDb = query().orderByAsc("sort").list();
            if (CollectionUtil.isEmpty(fromDb)) {
                log.warn("数据库中未找到任何商铺分类信息");
                // 返回 null ⇒ 组件写 60 秒空值标记防穿透（SPEC-05 G5）
                return null;
            }
            log.info("从数据库中查询到{}个商铺分类", fromDb.size());
            return fromDb;
        });

        if (typeList == null) {
            log.info("商铺分类为空（命中空值标记或库中确无数据）");
            return Result.fail("列表信息不存在");
        }

        if (log.isDebugEnabled()) {
            for (ShopType shopType : typeList) {
                log.debug("商铺分类详情 - ID: {}, 名称: {}, 图标: {}, 排序: {}",
                        shopType.getId(), shopType.getName(), shopType.getIcon(), shopType.getSort());
            }
        }
        log.info("商铺分类查询完成，共返回{}个分类", typeList.size());
        return Result.ok(typeList);
    }
}
