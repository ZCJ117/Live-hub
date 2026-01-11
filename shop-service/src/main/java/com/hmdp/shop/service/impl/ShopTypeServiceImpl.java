package com.hmdp.shop.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.shop.mapper.ShopTypeMapper;
import com.hmdp.shop.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

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

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result queryList() {
        log.info("开始查询商铺分类列表");

        //从redis中查询所有分类
        String shopTypeStr = redisTemplate.opsForValue().get(SHOP_LIST_KEY);
        if (StrUtil.isNotBlank(shopTypeStr)) {
            //查询到，直接返回
            log.info("从Redis缓存中获取到商铺分类数据");

            List<ShopType> types = JSONUtil.toList(shopTypeStr, ShopType.class);
            log.info("成功解析Redis中的商铺分类数据，解析出{}个分类", types.size());

            // 记录详细的分类信息
            if (log.isDebugEnabled()) {
                for (ShopType shopType : types) {
                    log.debug("商铺分类详情 - ID: {}, 名称: {}, 图标: {}, 排序: {}",
                            shopType.getId(), shopType.getName(), shopType.getIcon(), shopType.getSort());
                }
            }

            log.info("商铺分类查询成功，从缓存返回{}个分类", types.size());
            return Result.ok(types);
        }

        log.info("Redis中未找到商铺分类缓存，开始查询数据库");

        //redis中没有，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //数据库中没有，报错
        if (CollectionUtil.isEmpty(typeList)) {
            log.warn("数据库中未找到任何商铺分类信息");
            return Result.fail("列表信息不存在");
        }

        log.info("从数据库中查询到{}个商铺分类", typeList.size());

        // 记录详细的分类信息
        for (ShopType shopType : typeList) {
            log.info("商铺分类 - ID: {}, 名称: {}, 图标: {}, 排序: {}",
                    shopType.getId(), shopType.getName(), shopType.getIcon(), shopType.getSort());
        }

        //数据库中有，存到redis
        String jsonStr = JSONUtil.toJsonStr(typeList);
        try {
            redisTemplate.opsForValue().set(SHOP_LIST_KEY, jsonStr, 30, TimeUnit.MINUTES);
            log.info("成功将商铺分类数据存入Redis缓存，数据长度：{}", jsonStr.length());
        } catch (Exception e) {
            log.error("将商铺分类数据存入Redis时发生异常", e);
            // 即使Redis操作失败，仍然返回数据库查询结果
        }

        log.info("商铺分类查询完成，共返回{}个分类", typeList.size());
        return Result.ok(typeList);
    }
}