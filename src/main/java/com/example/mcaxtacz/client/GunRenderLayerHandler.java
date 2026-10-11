package com.example.mcaxtacz.client;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Set;

@EventBusSubscriber(
        modid = "mcaxtacz",
        value = {Dist.CLIENT}
)
public class GunRenderLayerHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GunRenderLayerHandler.class);

    // ResourceLocation trong 1.21.1 dùng ResourceLocation.fromNamespaceAndPath hoặc ResourceLocation.parse
    private static final Set<ResourceLocation> VILLAGER_ENTITY_IDS = Set.of(
            ResourceLocation.fromNamespaceAndPath("mca", "male_villager"),
            ResourceLocation.fromNamespaceAndPath("mca", "female_villager")
    );
    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (ResourceLocation id : VILLAGER_ENTITY_IDS) {
            // Thay ForgeRegistries.ENTITY_TYPES.getValue(id) -> BuiltInRegistries.ENTITY_TYPE.get(id)
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);

            if (type == null) {
                LOGGER.warn("Không tìm thấy EntityType với ID: {}", id);
            } else {
                EntityRenderer<?> renderer = event.getRenderer(type);

                if (renderer instanceof LivingEntityRenderer<?, ?> livingRenderer) {
                    addItemInHandLayer(livingRenderer, event);
                } else {
                    LOGGER.warn("Renderer của {} không phải là LivingEntityRenderer, không thể thêm ItemInHandLayer", id);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends LivingEntity, M extends net.minecraft.client.model.EntityModel<T>> void addItemInHandLayer(
            LivingEntityRenderer<?, ?> renderer,
            EntityRenderersEvent.AddLayers event
    ) {
        try {
            // m_234598_() -> getItemInHandRenderer()
            ItemInHandLayer layer = new ItemInHandLayer(
                    (LivingEntityRenderer) renderer,
                    event.getContext().getItemInHandRenderer()
            );

            // Không cần Reflection nữa, gọi trực tiếp addLayer
            ((LivingEntityRenderer) renderer).addLayer(layer);
            LOGGER.info("Đã thêm thành công ItemInHandLayer vào MCA Renderer: {}", renderer.getClass().getSimpleName());
        } catch (Exception e) {
            LOGGER.error("Không thể thêm ItemInHandLayer vào MCA Renderer", e);
        }
    }
    private static void addItemInHandLayer(LivingEntityRenderer<?, ?> renderer, ItemInHandRenderer itemInHandRenderer) {
        try {
            RenderLayer layer = new ItemInHandLayer(renderer, itemInHandRenderer);
            invokeAddLayer(renderer, layer);
        } catch (Exception e) {
            LOGGER.error("No se pudo aÃ±adir la ItemInHandLayer al renderer de MCA", e);
        }

    }

    private static void invokeAddLayer(LivingEntityRenderer<?, ?> renderer, RenderLayer layer) throws Exception {
        Exception lastError = null;

        for(String candidate : new String[]{"addLayer", "m_115326_"}) {
            try {
                Method addLayer = LivingEntityRenderer.class.getDeclaredMethod(candidate, RenderLayer.class);
                addLayer.setAccessible(true);
                addLayer.invoke(renderer, layer);
                return;
            } catch (NoSuchMethodException e) {
                lastError = e;
            }
        }

        throw lastError;
    }
}
