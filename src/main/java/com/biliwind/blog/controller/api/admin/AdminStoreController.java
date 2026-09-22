package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.StoreItem;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Path("/api/admin/store")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.enterprise.context.ApplicationScoped
public class AdminStoreController {

    @GET
    public Map<String, Object> list(@QueryParam("page") @DefaultValue("1") int page,
                                    @QueryParam("pageSize") @DefaultValue("20") int pageSize,
                                    @QueryParam("name") String name,
                                    @QueryParam("type") String type) {

        StringBuilder jpql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (name != null && !name.trim().isEmpty()) {
            jpql.append(" and name like :name");
            params.put("name", "%" + name + "%");
        }
        if (type != null && !type.trim().isEmpty()) {
            jpql.append(" and type = :type");
            params.put("type", type);
        }

        PanacheQuery<StoreItem> query = StoreItem.find(jpql.toString(), Sort.by("id").descending(), params);
        query.page(Page.of(Math.max(0, page - 1), pageSize));

        Map<String, Object> result = new HashMap<>();
        result.put("items", query.list());
        result.put("total", query.count());
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    @POST
    @Transactional
    public StoreItem create(StoreItem item) {
        if (item.name == null || item.name.trim().isEmpty()) {
            throw new BadRequestException("物品名称不能为空");
        }
        if (item.price == null || item.price < 0) {
            throw new BadRequestException("物品价格必须大于等于0");
        }
        if (item.width == null || item.width <= 0 || item.height == null || item.height <= 0) {
            throw new BadRequestException("物品尺寸必须大于0");
        }
        if (item.maxStackSize == null || item.maxStackSize <= 0
                || (!item.stackable && item.maxStackSize != 1)) {
            throw new BadRequestException("物品堆叠上限非法");
        }
        if (item.itemCode == null || item.itemCode.isBlank()) item.itemCode = "store:" + UUID.randomUUID();
        item.persist();
        return item;
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public StoreItem update(@PathParam("id") Long id, StoreItem updateData) {
        StoreItem existing = StoreItem.findById(id);
        if (existing == null) {
            throw new BadRequestException("物品不存在");
        }

        if (updateData.name != null && !updateData.name.trim().isEmpty()) {
            existing.name = updateData.name;
        }
        if (updateData.description != null) existing.description = updateData.description;
        if (updateData.price != null && updateData.price >= 0) existing.price = updateData.price;
        if (updateData.rarity != null) existing.rarity = updateData.rarity;
        if (updateData.type != null) existing.type = updateData.type;
        if (updateData.width != null && updateData.width > 0) existing.width = updateData.width;
        if (updateData.height != null && updateData.height > 0) existing.height = updateData.height;
        if (updateData.maxStackSize != null && updateData.maxStackSize > 0) existing.maxStackSize = updateData.maxStackSize;
        existing.stackable = updateData.stackable;
        existing.containerRows = updateData.containerRows;
        existing.containerColumns = updateData.containerColumns;
        existing.extraInfo = updateData.extraInfo;
        existing.status = updateData.status;

        return existing;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Map<String, Boolean> delete(@PathParam("id") Long id) {
        StoreItem item = StoreItem.findById(id);
        if (item != null) {
            item.delete();
        }
        Map<String, Boolean> res = new HashMap<>();
        res.put("success", true);
        return res;
    }
}
