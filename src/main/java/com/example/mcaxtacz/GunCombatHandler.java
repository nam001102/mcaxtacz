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

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@EventBusSubscriber(modid = mcaxtacz.MODID)
public class GunCombatHandler {
    private static final long RAPID_FIRE_INTERVAL_MS = 110L;
    private static final long BURST_PAUSE_MIN_MS = 350L;
    private static final long BURST_PAUSE_MAX_MS = 1100L;
    private static final double MAX_ENGAGE_RANGE = 48.0D;

    private static final Map<UUID, Long> nextShotTime = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> shotsLeftInBurst = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> recoilPitch = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> recoilYaw = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onEntityTick(LivingEvent.LivingTickEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Villager villager && MCACompatibilityHelper.isMCAVillager(villager)) {
            if (!villager.level().isClientSide) {
                ItemStack mainHand = villager.getMainHandItem();

                if (mainHand.getItem() instanceof IGun gun) {
                    LivingEntity target = getAttackTarget(villager).orElse(null);
                    UUID id = villager.getUUID();

                    if (target != null && target.isAlive()) {
                        if (!(villager.distanceToSqr(target) > 2304.0D)) {
                            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                            villager.getBrain().eraseMemory(MemoryModuleType.ATTACK_COOLING_DOWN);

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
                                        Container villagerInv = MCACompatibilityHelper.getMCAInventory(villager);

                                        if (villagerInv != null) {
                                            Optional<ItemStack> ammoInInv = findAmmoInContainer(villagerInv, targetAmmoId);

                                            if (ammoInInv.isEmpty()) {
                                                fetchAmmoFromNearbyChests(villager, targetAmmoId, gunData.getAmmoAmount());
                                                ammoInInv = findAmmoInContainer(villagerInv, targetAmmoId);
                                            }

                                            if (ammoInInv.isPresent()) {
                                                ItemStack ammoStack = ammoInInv.get();
                                                int maxCapacity = gunData.getAmmoAmount();
                                                int ammoToLoad = Math.min(ammoStack.getCount(), maxCapacity);

                                                ammoStack.shrink(ammoToLoad);
                                                gun.setCurrentAmmoCount(mainHand, ammoToLoad);

                                                syncMainHandToContainer(villager, mainHand);
                                                villagerInv.setChanged();

                                                IGunOperator operator = IGunOperator.fromLivingEntity(villager);
                                                if (isShotgunOrBolt(gun, mainHand)) {
                                                    operator.bolt();
                                                }

                                                villager.swing(InteractionHand.MAIN_HAND);
                                                broadcastVillagerMessage(villager, "§eReloading!");

                                                long reloadTime = isShotgunOrBolt(gun, mainHand) ? 2800L : 2200L;
                                                nextShotTime.put(id, now + reloadTime);
                                                return;
                                            }
                                        }
                                    }

                                    villager.swing(InteractionHand.MAIN_HAND);
                                    broadcastVillagerMessage(villager, "§cOut of ammo!");
                                    nextShotTime.put(id, now + 3000L);
                                    return;
                                }

                                // 🔫 FIRING MECHANIC
                                IGunOperator operator = IGunOperator.fromLivingEntity(villager);

                                int currentAmmoCheck = gun.getCurrentAmmoCount(mainHand);
                                if (currentAmmoCheck <= 0) {
                                    nextShotTime.put(id, now + 100L);
                                    return;
                                }

                                float pitch = villager.getXRot();
                                float yaw = villager.getYRot();

                                ShootResult result = operator.shoot(() -> pitch, () -> yaw);

                                if (result == ShootResult.NEED_BOLT) {
                                    operator.bolt();
                                    syncMainHandToContainer(villager, mainHand);
                                    nextShotTime.put(id, now + 500L);
                                    return;
                                }

                                if (result == ShootResult.SUCCESS) {
                                    villager.swing(InteractionHand.MAIN_HAND);

                                    boolean isPumpOrBolt = isShotgunOrBolt(gun, mainHand);
                                    ItemStack updatedHand = villager.getMainHandItem();

                                    int liveAmmo = gun.getCurrentAmmoCount(updatedHand);
                                    int newAmmo = Math.max(0, liveAmmo - 1);

                                    gun.setCurrentAmmoCount(updatedHand, newAmmo);
                                    syncMainHandToContainer(villager, updatedHand);

                                    applyRecoilKick(id);

                                    long shotDelay = isPumpOrBolt ? 750L : RAPID_FIRE_INTERVAL_MS;

                                    int left = shotsLeftInBurst.getOrDefault(id, randomBurstLength()) - 1;
                                    if (left <= 0) {
                                        long pause = ThreadLocalRandom.current().nextLong(BURST_PAUSE_MIN_MS, BURST_PAUSE_MAX_MS + 1L);
                                        nextShotTime.put(id, now + pause);
                                        shotsLeftInBurst.put(id, randomBurstLength());
                                    } else {
                                        shotsLeftInBurst.put(id, left);
                                        nextShotTime.put(id, now + shotDelay);
                                    }
                                } else {
                                    nextShotTime.put(id, now + 100L);
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

    private static boolean isShotgunOrBolt(IGun gun, ItemStack stack) {
        ResourceLocation gunId = gun.getGunId(stack);
        if (gunId == null) return false;
        String path = gunId.getPath().toLowerCase();
        return path.contains("shotgun") || path.contains("m870") || path.contains("db")
                || path.contains("sniper") || path.contains("kar98") || path.contains("bolt");
    }

    private static void syncMainHandToContainer(Villager villager, ItemStack stack) {
        Container inventory = MCACompatibilityHelper.getMCAInventory(villager);
        if (inventory != null && inventory.getContainerSize() > 0) {
            inventory.setItem(0, stack);
            inventory.setChanged();
        }
    }

    private static Optional<ItemStack> findAmmoInContainer(Container container, ResourceLocation targetAmmoId) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && isMatchingAmmo(stack, targetAmmoId)) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    private static void fetchAmmoFromNearbyChests(Villager villager, ResourceLocation targetAmmoId, int amountNeeded) {
        BlockPos villagerPos = villager.blockPosition();
        int radius = 8;

        for (BlockPos pos : BlockPos.betweenClosed(
                villagerPos.offset(-radius, -2, -radius),
                villagerPos.offset(radius, 2, radius))) {

            BlockEntity be = villager.level().getBlockEntity(pos);

            if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stackInChest = container.getItem(i);

                    if (!stackInChest.isEmpty() && isMatchingAmmo(stackInChest, targetAmmoId)) {
                        int countToTake = Math.min(stackInChest.getCount(), amountNeeded);

                        ItemStack takenAmmo = stackInChest.copy();
                        takenAmmo.setCount(countToTake);

                        stackInChest.shrink(countToTake);
                        if (stackInChest.isEmpty()) {
                            container.setItem(i, ItemStack.EMPTY);
                        }
                        container.setChanged();

                        Container villagerInv = MCACompatibilityHelper.getMCAInventory(villager);
                        if (villagerInv != null) {
                            // Add item to container safely
                            for (int slot = 0; slot < villagerInv.getContainerSize(); slot++) {
                                ItemStack slotItem = villagerInv.getItem(slot);
                                if (slotItem.isEmpty()) {
                                    villagerInv.setItem(slot, takenAmmo);
                                    break;
                                } else if (ItemStack.isSameItemSameTags(slotItem, takenAmmo) && slotItem.getCount() < slotItem.getMaxStackSize()) {
                                    int space = slotItem.getMaxStackSize() - slotItem.getCount();
                                    int toAdd = Math.min(space, takenAmmo.getCount());
                                    slotItem.grow(toAdd);
                                    takenAmmo.shrink(toAdd);
                                    if (takenAmmo.isEmpty()) break;
                                }
                            }
                            villagerInv.setChanged();
                        }

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

    private static void broadcastVillagerMessage(Villager villager, String message) {
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
    public static void onLivingHurt(LivingHurtEvent event) {
        Entity attacker = event.getSource().getEntity();
        Entity directAttacker = event.getSource().getDirectEntity();

        if (attacker instanceof Villager villager && MCACompatibilityHelper.isMCAVillager(villager)) {
            ItemStack mainHandItem = villager.getMainHandItem();

            if (mainHandItem.getItem() instanceof IGun gun) {
                int currentAmmo = gun.getCurrentAmmoCount(mainHandItem);
                if (currentAmmo <= 0) {
                    event.setCanceled(true);
                    broadcastVillagerMessage(villager, "§c[Out of Ammo!]");
                    return;
                }

                if (directAttacker == villager) {
                    event.setCanceled(true);
                }
            }
        }
    }

    private static boolean isMatchingAmmo(ItemStack stack, ResourceLocation targetAmmoId) {
        if (targetAmmoId == null || stack.isEmpty()) return false;

        if (stack.getItem() instanceof IGun) {
            return false;
        }

        if (stack.getItem() instanceof IAmmo ammoItem) {
            ResourceLocation stackAmmoId = ammoItem.getAmmoId(stack);
            return targetAmmoId.equals(stackAmmoId);
        }
        return false;
    }

    private static Optional<LivingEntity> getAttackTarget(Villager villager) {
        Brain<?> brain = villager.getBrain();
        Optional<LivingEntity> target = brain.getMemory(MemoryModuleType.ATTACK_TARGET);
        if (target.isPresent()) {
            return target;
        }

        // Fallback sang phương thức getTarget() truyền thống mà Archer sử dụng
        LivingEntity mobTarget = villager.getTarget();
        if (mobTarget != null && mobTarget.isAlive()) {
            return Optional.of(mobTarget);
        }

        return Optional.empty();
    }

    private static void aimAt(Villager villager, LivingEntity target, UUID id) {
        Vec3 from = villager.getEyePosition();
        Vec3 diff = target.getEyePosition().subtract(from);

        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);

        float baseYaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F);
        float basePitch = (float) (-Math.toDegrees(Math.atan2(diff.y, horizontalDist)));

        float currentRecoilPitch = recoilPitch.getOrDefault(id, 0.0F) * 0.75F;
        float currentRecoilYaw = recoilYaw.getOrDefault(id, 0.0F) * 0.75F;

        recoilPitch.put(id, currentRecoilPitch);
        recoilYaw.put(id, currentRecoilYaw);

        float yaw = baseYaw + currentRecoilYaw;
        float pitch = basePitch - currentRecoilPitch;

        villager.setYRot(yaw);
        villager.setXRot(pitch);

        villager.yRotO = yaw;
        villager.xRotO = pitch;

        villager.yHeadRot = yaw;
        villager.yBodyRot = yaw;
    }

    private static void applyRecoilKick(UUID id) {
        float newPitch = Math.min(recoilPitch.getOrDefault(id, 0.0F) + 4.0F, 18.0F);
        recoilPitch.put(id, newPitch);
        float yawKick = (float) ThreadLocalRandom.current().nextDouble(-1.5F, 1.5F);
        recoilYaw.put(id, recoilYaw.getOrDefault(id, 0.0F) + yawKick);
    }
}