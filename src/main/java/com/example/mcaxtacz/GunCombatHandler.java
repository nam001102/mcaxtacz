package com.example.mcaxtacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(
        modid = "mcaxtacz"
)
public class GunCombatHandler {
    private static final long RAPID_FIRE_INTERVAL_MS = 110L;
    private static final long BURST_PAUSE_MIN_MS = 350L;
    private static final long BURST_PAUSE_MAX_MS = 1100L;
    private static final int BURST_MIN_SHOTS = 1;
    private static final int BURST_MAX_SHOTS = 5;
    private static final double MAX_ENGAGE_RANGE = 48.0D;

    private static final Map<UUID, Long> nextShotTime = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> shotsLeftInBurst = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> recoilPitch = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> recoilYaw = new ConcurrentHashMap<>();

    private static final float RECOIL_KICK_PITCH = 4.0F;
    private static final float RECOIL_KICK_YAW_MAX = 1.5F;
    private static final float RECOIL_MAX_PITCH = 18.0F;
    private static final float RECOIL_RECOVERY = 0.75F;

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity instanceof VillagerEntityMCA villager) {
            if (!villager.level().isClientSide) {
                ItemStack mainHand = villager.getMainHandItem();

                if (mainHand.getItem() instanceof IGun gun) {
                    LivingEntity target = getAttackTarget(villager).orElse(null);
                    UUID id = villager.getUUID();

                    if (target != null && target.isAlive()) {
                        if (!(villager.distanceToSqr(target) > 2304.0D)) {
                            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                            aimAt(villager, target, id);

                            long now = System.currentTimeMillis();
                            Long next = nextShotTime.get(id);

                            if (next == null || now >= next) {
                                int currentAmmo = gun.getCurrentAmmoCount(mainHand);

                                // 🔄 RELOAD MECHANIC
                                if (currentAmmo <= 0) {
                                    ResourceLocation gunId = gun.getGunId(mainHand);
                                    Optional<GunData> gunDataOpt = TimelessAPI.getCommonGunIndex(gunId)
                                            .map(CommonGunIndex::getGunData);

                                    if (gunDataOpt.isPresent()) {
                                        GunData gunData = gunDataOpt.get();
                                        ResourceLocation targetAmmoId = gunData.getAmmoId();
                                        Container villagerInv = villager.getInventory();

                                        Optional<ItemStack> ammoInInv = InventoryUtils.stream(villagerInv)
                                                .filter(stack -> !stack.isEmpty() && isMatchingAmmo(stack, targetAmmoId))
                                                .findFirst();

                                        if (ammoInInv.isEmpty()) {
                                            fetchAmmoFromNearbyChests(villager, targetAmmoId, gunData.getAmmoAmount());
                                            ammoInInv = InventoryUtils.stream(villagerInv)
                                                    .filter(stack -> !stack.isEmpty() && isMatchingAmmo(stack, targetAmmoId))
                                                    .findFirst();
                                        }

                                        if (ammoInInv.isPresent()) {
                                            ItemStack ammo = ammoInInv.get();
                                            int maxCapacity = gunData.getAmmoAmount();
                                            int ammoToLoad = Math.min(ammo.getCount(), maxCapacity);

                                            ammo.shrink(ammoToLoad);

                                            // 🟢 UPDATE AMMO & SYNC CONTAINER SLOT IMMEDIATELY
                                            gun.setCurrentAmmoCount(mainHand, ammoToLoad);
                                            syncMainHandToContainer(villager, mainHand);

                                            villager.swing(InteractionHand.MAIN_HAND);
                                            broadcastVillagerMessage(villager, "§eReloading!");
                                            nextShotTime.put(id, now + 2500L);
                                            return;
                                        }
                                    }

                                    villager.swing(InteractionHand.MAIN_HAND);
                                    broadcastVillagerMessage(villager, "§cOut of ammo!");
                                    nextShotTime.put(id, now + 3000L);
                                    return;
                                }

                                // 🔫 FIRING MECHANIC
                                IGunOperator operator = IGunOperator.fromLivingEntity(villager);
                                float pitch = villager.getXRot();
                                float yaw = villager.getYRot();

                                ShootResult result = operator.shoot(() -> pitch, () -> yaw);
                                villager.swing(InteractionHand.MAIN_HAND);

                                if (result == ShootResult.SUCCESS) {
                                    // 🟢 DEDUCT AMMO & SYNC CONTAINER SLOT
                                    gun.setCurrentAmmoCount(mainHand, currentAmmo - 1);
                                    syncMainHandToContainer(villager, mainHand);

                                    applyRecoilKick(id);

                                    int left = shotsLeftInBurst.getOrDefault(id, randomBurstLength()) - 1;
                                    if (left <= 0) {
                                        long pause = ThreadLocalRandom.current().nextLong(BURST_PAUSE_MIN_MS, BURST_PAUSE_MAX_MS + 1L);
                                        nextShotTime.put(id, now + pause);
                                        shotsLeftInBurst.put(id, randomBurstLength());
                                    } else {
                                        shotsLeftInBurst.put(id, left);
                                        nextShotTime.put(id, now + RAPID_FIRE_INTERVAL_MS);
                                    }
                                } else {
                                    nextShotTime.put(id, now + 150L);
                                }
                            }
                        }
                    } else {
                        nextShotTime.remove(id);
                        shotsLeftInBurst.remove(id);
                        recoilPitch.remove(id);
                        recoilYaw.remove(id);
                    }
                }
            }
        }
    }

    /**
     * Force syncs the updated main-hand ItemStack back into MCA's inventory slot 0.
     * Prevents item splitting and ghost duplicate creation.
     */
    private static void syncMainHandToContainer(VillagerEntityMCA villager, ItemStack mainHand) {
        Container inventory = villager.getInventory();
        if (inventory.getContainerSize() > 0) {
            inventory.setItem(0, mainHand);
            inventory.setChanged();
        }
    }

    private static void fetchAmmoFromNearbyChests(VillagerEntityMCA villager, ResourceLocation targetAmmoId, int amountNeeded) {
        BlockPos villagerPos = villager.blockPosition();
        int radius = 8;

        for (BlockPos pos : BlockPos.betweenClosed(
                villagerPos.offset(-radius, -2, -radius),
                villagerPos.offset(radius, 2, radius))) {

            BlockEntity be = villager.level().getBlockEntity(pos);

            if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stackInChest = container.getItem(i);

                    // STRICT CHECK: Ensure item is valid ammo AND NOT a gun before moving it
                    if (!stackInChest.isEmpty() && isMatchingAmmo(stackInChest, targetAmmoId)) {
                        int countToTake = Math.min(stackInChest.getCount(), amountNeeded);

                        ItemStack takenAmmo = stackInChest.copy();
                        takenAmmo.setCount(countToTake);

                        stackInChest.shrink(countToTake);
                        if (stackInChest.isEmpty()) {
                            container.setItem(i, ItemStack.EMPTY);
                        }
                        container.setChanged();

                        // Insert taken ammo into inventory
                        villager.getInventory().addItem(takenAmmo);

                        broadcastVillagerMessage(villager, "§b[Looted ammo from chest]");
                        return;
                    }
                }
            }
        }
    }

    private static int randomBurstLength() {
        return ThreadLocalRandom.current().nextInt(1, 6);
    }

//    private static void cleanDuplicateGunsInInventory(VillagerEntityMCA villager, ItemStack mainHandGun) {
//        Container inventory = villager.getInventory();
//
//        for (int i = 0; i < inventory.getContainerSize(); i++) {
//            ItemStack stack = inventory.getItem(i);
//
//            // Nếu tìm thấy item là súng
//            if (!stack.isEmpty() && stack.getItem() instanceof IGun) {
//                // 🛑 Bỏ qua khẩu súng Villager đang cầm trên tay
//                // So sánh tham chiếu (stack == mainHandGun) hoặc so sánh slot
//                if (stack == mainHandGun) {
//                    continue;
//                }
//
//                // Nếu không phải khẩu súng trên tay -> Đây là súng duplicate -> Xóa bỏ
//                inventory.setItem(i, ItemStack.EMPTY);
//                inventory.setChanged();
//            }
//        }
//    }

    private static void broadcastVillagerMessage(VillagerEntityMCA villager, String message) {
        villager.level().getEntitiesOfClass(
                net.minecraft.world.entity.player.Player.class,
                villager.getBoundingBox().inflate(16.0D)
        ).forEach(player ->
                player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                                "[" + villager.getDisplayName().getString() + "] " + message
                        )
                )
        );
    }
    @SubscribeEvent
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        // getEntity(): Kẻ chịu trách nhiệm chính (Villager)
        Entity attacker = event.getSource().getEntity();
        // getDirectEntity(): Thực thể trực tiếp gây sát thương (Villager nếu cận chiến, Viên đạn nếu bắn súng)
        Entity directAttacker = event.getSource().getDirectEntity();

        if (attacker instanceof VillagerEntityMCA villager) {
            ItemStack mainHandItem = villager.getMainHandItem();

            if (mainHandItem.getItem() instanceof IGun) {
                // CHỈ hủy sát thương nếu Villager trực tiếp va chạm cận chiến với mục tiêu.
                // Nếu directAttacker != villager (tức là do viên đạn TaCZ bay vào), cho phép gây sát thương.
                if (directAttacker == villager) {
                    event.setCanceled(true);
                }
            }
        }
    }

    private static boolean isMatchingAmmo(ItemStack stack, ResourceLocation targetAmmoId) {
        if (targetAmmoId == null || stack.isEmpty()) return false;

        // ⛔ Đảm bảo ITEM KHÔNG PHẢI LÀ SÚNG để tránh nhân bản súng
        if (stack.getItem() instanceof IGun) {
            return false;
        }

        if (stack.getItem() instanceof IAmmo ammoItem) {
            ResourceLocation stackAmmoId = ammoItem.getAmmoId(stack);
            return targetAmmoId.equals(stackAmmoId);
        }
        return false;
    }

    private static Optional<LivingEntity> getAttackTarget(VillagerEntityMCA villager) {
        Brain<?> brain = villager.getBrain();
        return brain.getMemory(MemoryModuleType.ATTACK_TARGET);
    }

    private static void aimAt(VillagerEntityMCA villager, LivingEntity target, UUID id) {
        // m_146892_() -> getEyePosition()
        Vec3 from = villager.getEyePosition();
        // m_82546_() -> subtract()
        Vec3 diff = target.getEyePosition().subtract(from);

        // f_82479_ -> x, f_82480_ -> y, f_82481_ -> z
        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);

        float baseYaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F);
        float basePitch = (float) (-Math.toDegrees(Math.atan2(diff.y, horizontalDist)));

        float currentRecoilPitch = recoilPitch.getOrDefault(id, 0.0F) * 0.75F;
        float currentRecoilYaw = recoilYaw.getOrDefault(id, 0.0F) * 0.75F;

        recoilPitch.put(id, currentRecoilPitch);
        recoilYaw.put(id, currentRecoilYaw);

        float yaw = baseYaw + currentRecoilYaw;
        float pitch = basePitch - currentRecoilPitch;

        // m_146922_() -> setYRot()
        villager.setYRot(yaw);
        // m_146926_() -> setXRot()
        villager.setXRot(pitch);

        // f_19859_ -> yRotO (Yaw vòng quay trước - dùng cho interpolation render)
        villager.yRotO = yaw;
        // f_19860_ -> xRotO (Pitch vòng quay trước)
        villager.xRotO = pitch;

        // f_20885_ -> yHeadRot (Góc xoay của đầu)
        villager.yHeadRot = yaw;
        // f_20883_ -> yBodyRot (Góc xoay của thân)
        villager.yBodyRot = yaw;
    }

    private static void applyRecoilKick(UUID id) {
        float newPitch = Math.min((Float)recoilPitch.getOrDefault(id, 0.0F) + 4.0F, 18.0F);
        recoilPitch.put(id, newPitch);
        float yawKick = (float)ThreadLocalRandom.current().nextDouble((double)-1.5F, (double)1.5F);
        recoilYaw.put(id, (Float)recoilYaw.getOrDefault(id, 0.0F) + yawKick);
    }
}
