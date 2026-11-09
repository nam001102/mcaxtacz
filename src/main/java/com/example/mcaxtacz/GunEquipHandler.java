package com.example.mcaxtacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;

import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

import java.util.Optional;

@EventBusSubscriber(modid = mcaxtacz.MODID)
public class GunEquipHandler {

    // Ưu tiên HIGHEST để chặn hoàn toàn GUI của MCA khi player đang cầm súng trên tay
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget();

        if (target instanceof Villager villager && MCACompatibilityHelper.isMCAVillager(villager)) {
            Player player = event.getEntity();
            InteractionHand hand = event.getHand();

            ItemStack held = player.getItemInHand(hand);

            if (!held.isEmpty() && held.getItem() instanceof IGun) {
                // Chặn sự kiện ngay cả ở phía Client để ngắt lệnh mở GUI màn hình MCA
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);

                if (!villager.level().isClientSide()) {
                    // 1. Kiểm tra súng cũ ở cả tay chính LẪN kho đồ MCA
                    ItemStack currentMainHand = villager.getItemBySlot(EquipmentSlot.MAINHAND);

                    // Xóa hoàn toàn súng cũ khỏi rương và trả lại cho người chơi
                    clearAndReturnOldGuns(player, villager, currentMainHand);

                    // 2. Trang bị súng mới
                    equipGun(villager, held);

                    // 3. Trừ 1 súng trên tay người chơi (nếu không ở Creative)
                    if (!player.getAbilities().instabuild) {
                        held.shrink(1);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityTick(LivingEvent.LivingTickEvent event) {
        Entity entity = event.getEntity();

        if (entity instanceof Villager villager && MCACompatibilityHelper.isMCAVillager(villager)) {
            if (!villager.level().isClientSide()) {
                Container inventory = MCACompatibilityHelper.getMCAInventory(villager);
                if (inventory == null) return;

                ItemStack current = villager.getItemBySlot(EquipmentSlot.MAINHAND);

                // Chỉ quét rương nếu tay chính chưa cầm súng
                if (!(current.getItem() instanceof IGun)) {
                    Optional<ItemStack> bestGun = Optional.empty();
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        ItemStack stack = inventory.getItem(i);
                        if (!stack.isEmpty() && stack.getItem() instanceof IGun) {
                            bestGun = Optional.of(stack);
                            break;
                        }
                    }

                    if (bestGun.isPresent()) {
                        equipGun(villager, bestGun.get());
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() == EquipmentSlot.MAINHAND) {
            if (event.getEntity() instanceof Villager villager && MCACompatibilityHelper.isMCAVillager(villager)) {
                ItemStack from = event.getFrom();
                ItemStack to = event.getTo();

                // Ngăn MCA AI tước súng khi chuyển sang trạng thái nghỉ (non-combat)
                if (!from.isEmpty() && from.getItem() instanceof IGun && !(to.getItem() instanceof IGun)) {
                    // Kiểm tra xem có súng nào trong rương không, nếu không còn súng mới trả về từ
                    Container inventory = MCACompatibilityHelper.getMCAInventory(villager);
                    if (inventory != null) {
                        boolean hasGunInInv = false;
                        for (int i = 0; i < inventory.getContainerSize(); i++) {
                            if (inventory.getItem(i).getItem() instanceof IGun) {
                                hasGunInInv = true;
                                break;
                            }
                        }
                        if (hasGunInInv) {
                            villager.setItemSlot(EquipmentSlot.MAINHAND, from);
                        }
                    }
                }
            }
        }
    }

    private static void equipGun(Villager villager, ItemStack gunStack) {
        ItemStack toEquip = gunStack.copy();
        toEquip.setCount(1);

        topUpAmmo(toEquip);

        // Gán vào tay chính
        villager.setItemSlot(EquipmentSlot.MAINHAND, toEquip);
        villager.setDropChance(EquipmentSlot.MAINHAND, 0.0F);

        // Đồng bộ an toàn vào MCA Container
        syncGunToContainer(villager, toEquip);

        // Reset capability TaCZ
        IGunOperator.fromLivingEntity(villager).initialData();
    }

    /**
     * Dọn dẹp sạch toàn bộ súng cũ trong rương lẫn trên tay, tránh việc MCA backup súng cũ
     */
    private static void clearAndReturnOldGuns(Player player, Villager villager, ItemStack mainHandGun) {
        // 1. Trả súng trên tay chính cho người chơi nếu có
        if (!mainHandGun.isEmpty() && mainHandGun.getItem() instanceof IGun) {
            giveItemToPlayerOrDrop(player, villager, mainHandGun.copy());
            villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }

        // 2. Quét rương MCA, trả bất kỳ khẩu súng dư thừa nào cho player và xóa sạch slot rương
        Container inventory = MCACompatibilityHelper.getMCAInventory(villager);
        if (inventory == null) return;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slotStack = inventory.getItem(i);
            if (!slotStack.isEmpty() && slotStack.getItem() instanceof IGun) {
                // Tránh trả trùng nếu nó chính là stack vừa trả ở tay chính
                if (mainHandGun.isEmpty() || !ItemStack.matches(slotStack, mainHandGun)) {
                    giveItemToPlayerOrDrop(player, villager, slotStack.copy());
                }
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        inventory.setChanged();
    }

    private static void syncGunToContainer(Villager villager, ItemStack gunStack) {
        Container inventory = MCACompatibilityHelper.getMCAInventory(villager);
        if (inventory == null) return;

        // Tìm slot trống đầu tiên hoặc slot đã có súng đó để gán vào, không đè slot 0 vô điều kiện
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty() || slot.getItem() == gunStack.getItem()) {
                inventory.setItem(i, gunStack);
                inventory.setChanged();
                return;
            }
        }
    }

    public static void topUpAmmo(ItemStack gunStack) {
        IGun iGun = IGun.getIGunOrNull(gunStack);
        if (iGun != null) {
            int maxAmmo = TimelessAPI.getCommonGunIndex(iGun.getGunId(gunStack))
                    .map(index -> index.getGunData().getAmmoAmount())
                    .orElse(9999);

            iGun.setCurrentAmmoCount(gunStack, maxAmmo);
            iGun.setBulletInBarrel(gunStack, true);
        }
    }

    private static void giveItemToPlayerOrDrop(Player player, Villager villager, ItemStack stack) {
        if (stack.isEmpty()) return;
        boolean added = player.getInventory().add(stack);
        if (!added || !stack.isEmpty()) {
            ItemEntity itemEntity = new ItemEntity(villager.level(), villager.getX(), villager.getY(), villager.getZ(), stack);
            villager.level().addFreshEntity(itemEntity);
        }
    }
}