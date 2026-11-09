package com.example.mcaxtacz.client;

import com.example.mcaxtacz.mcaxtacz;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Set;

@EventBusSubscriber(
        modid = mcaxtacz.MODID,
        value = {Dist.CLIENT}
)
public class GunRenderLayerHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GunRenderLayerHandler.class);

    private static final Set<ResourceLocation> VILLAGER_ENTITY_IDS = Set.of(
            new ResourceLocation("mca", "male_villager"),
            new ResourceLocation("mca", "female_villager")
    );

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (ResourceLocation id : VILLAGER_ENTITY_IDS) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);

            if (type == null) {
                LOGGER.warn("Không tìm thấy EntityType với ID: {}", id);
            } else {
                // Safely retrieve renderer by casting the EntityType wildcard
                EntityRenderer<?> renderer = getLivingRenderer(event, type);

                if (renderer instanceof LivingEntityRenderer<?, ?> livingRenderer) {
                    addItemInHandLayer(livingRenderer, event.getContext().getItemInHandRenderer());
                } else {
                    LOGGER.warn("Renderer của {} không phải là LivingEntityRenderer, không thể thêm ItemInHandLayer", id);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityRenderer<?> getLivingRenderer(EntityRenderersEvent.AddLayers event, EntityType<?> type) {
        try {
            // Cast wildcard EntityType to EntityType<? extends LivingEntity> to satisfy event signature
            return event.getRenderer((EntityType<? extends LivingEntity>) type);
        } catch (ClassCastException e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addItemInHandLayer(LivingEntityRenderer<?, ?> renderer, ItemInHandRenderer itemInHandRenderer) {
        try {
            ItemInHandLayer layer = new ItemInHandLayer((LivingEntityRenderer) renderer, itemInHandRenderer);
            invokeAddLayer(renderer, layer);
            LOGGER.info("Đã thêm thành công ItemInHandLayer vào MCA Renderer: {}", renderer.getClass().getSimpleName());
        } catch (Exception e) {
            LOGGER.error("Không thể thêm ItemInHandLayer vào MCA Renderer", e);
        }
    }

    private static void invokeAddLayer(LivingEntityRenderer<?, ?> renderer, RenderLayer layer) throws Exception {
        Exception lastError = null;

        for (String candidate : new String[]{"addLayer", "m_115326_"}) {
            try {
                Method addLayer = LivingEntityRenderer.class.getDeclaredMethod(candidate, RenderLayer.class);
                addLayer.setAccessible(true);
                addLayer.invoke(renderer, layer);
                return;
            } catch (NoSuchMethodException e) {
                lastError = e;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
    }
}