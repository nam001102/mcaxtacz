package com.example.mcaxtacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;

import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.util.InventoryUtils;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Optional;

@EventBusSubscriber(
        modid = "mcaxtacz"
)
public class GunEquipHandler {
    private static final int NPC_AMMO_RESERVE = 9999;

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget();

        if (target instanceof VillagerEntityMCA villager) {
            Player player = event.getEntity();
            InteractionHand hand = event.getHand();

            // m_21120_(hand) -> getItemInHand(hand)
            ItemStack held = player.getItemInHand(hand);

            // m_41720_() -> getItem()
            if (held.getItem() instanceof IGun) {
                // m_9236_().m_5776_() -> level().isClientSide()
                if (!villager.level().isClientSide()) {
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);

                    equipGun(villager, held);

                    // m_150110_().f_35937_ -> getAbilities().instabuild
                    if (!player.getAbilities().instabuild) {
                        // m_41774_(1) -> shrink(1)
                        held.shrink(1);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();

        if (entity instanceof VillagerEntityMCA villager) {
            // m_9236_().m_5776_() -> level().isClientSide()
            if (!villager.level().isClientSide()) {
                // m_35311_() -> getInventory()
                Container inventory = villager.getInventory();

                // Tìm khẩu súng đầu tiên trong túi đồ của Villager
                // m_41720_() -> getItem()
                Optional<ItemStack> bestGun = InventoryUtils.stream(inventory)
                        .filter(stack -> stack.getItem() instanceof IGun)
                        .findFirst();

                // m_6844_(slot) -> getItemBySlot(slot)
                ItemStack current = villager.getItemBySlot(EquipmentSlot.MAINHAND);

                if (bestGun.isPresent()) {
                    ItemStack gun = bestGun.get();
                    boolean alreadyHoldingSameGun = current.getItem() instanceof IGun && current.getItem() == gun.getItem();

                    if (!alreadyHoldingSameGun) {
                        equipGun(villager, gun);
                    }
                } else if (current.getItem() instanceof IGun) {
                    // m_8061_(slot, stack) -> setItemSlot(slot, stack)
                    // f_41583_ -> ItemStack.EMPTY
                    villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        // getSlot() -> getSlot()
        if (event.getSlot() == EquipmentSlot.MAINHAND) {
            // getEntity() -> getEntity()
            if (event.getEntity() instanceof VillagerEntityMCA villager) {
                // getFrom() -> getFrom()
                ItemStack from = event.getFrom();
                // getTo() -> getTo()
                ItemStack to = event.getTo();

                // m_41720_() -> getItem()
                // Nếu vật phẩm cũ là Súng và vật phẩm mới KHÔNG phải là Súng, bắt buộc giữ lại Súng
                if (from.getItem() instanceof IGun && !(to.getItem() instanceof IGun)) {
                    // m_8061_(slot, stack) -> setItemSlot(slot, stack)
                    villager.setItemSlot(EquipmentSlot.MAINHAND, from);
                }
            }
        }
    }

    private static void equipGun(VillagerEntityMCA villager, ItemStack gunStack) {
        // m_41777_() -> copy()
        ItemStack toEquip = gunStack.copy();

        // m_41764_(1) -> setCount(1)
        toEquip.setCount(1);

        topUpAmmo(toEquip);

        // m_8061_(slot, stack) -> setItemSlot(slot, stack)
        villager.setItemSlot(EquipmentSlot.MAINHAND, toEquip);

        // m_21409_(slot, dropChance) -> setDropChance(slot, dropChance)
        // Thiết lập tỉ lệ rơi đồ khi chết là 0.0F (không rơi ra súng)
        villager.setDropChance(EquipmentSlot.MAINHAND, 0.0F);

        // Khởi tạo/Đồng bộ dữ liệu thao tác súng cho Entity từ TaCZ
        IGunOperator.fromLivingEntity(villager).initialData();
    }

    public static void topUpAmmo(ItemStack gunStack) {
        IGun iGun = IGun.getIGunOrNull(gunStack);
        if (iGun != null) {
            int maxAmmo = (Integer) TimelessAPI.getCommonGunIndex(iGun.getGunId(gunStack)).map((index) -> index.getGunData().getAmmoAmount()).orElse(9999);
            iGun.setCurrentAmmoCount(gunStack, maxAmmo);
            iGun.setBulletInBarrel(gunStack, true);
        }
    }
}

