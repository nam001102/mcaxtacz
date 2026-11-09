package com.example.mcaxtacz;

import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class MCACompatibilityHelper {

    /**
     * Kiểm tra chính xác Villager Entity thuộc MCA (hỗ trợ cả MCA 7.6.x lẫn 7.7.x)
     */
    public static boolean isMCAVillager(Entity entity) {
        if (!(entity instanceof Villager)) {
            return false;
        }

        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            String name = clazz.getName();
            // Quét chính xác class name của MCA Villager
            if (name.endsWith("VillagerEntityMCA") || name.contains("mca.entity")) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    /**
     * Lấy MCA Inventory Container an toàn qua Reflection (Method & Field Fallback)
     */
    public static Container getMCAInventory(Entity entity) {
        if (entity == null || !isMCAVillager(entity)) return null;

        Class<?> clazz = entity.getClass();

        // 1. Quét tìm public/declared method trả về Container (getInventory, getVillagerInventory,...)
        while (clazz != null && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && Container.class.isAssignableFrom(method.getReturnType())) {
                    try {
                        method.setAccessible(true);
                        Object result = method.invoke(entity);
                        if (result instanceof Container container) {
                            return container;
                        }
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }

        // 2. Fallback: Quét các field trong hierarchy nếu method bị obfuscate ở bản 7.6.26
        clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Container.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        Object result = field.get(entity);
                        if (result instanceof Container container) {
                            return container;
                        }
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }

        return null;
    }
}