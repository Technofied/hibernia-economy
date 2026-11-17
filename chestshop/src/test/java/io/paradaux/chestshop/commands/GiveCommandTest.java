package io.paradaux.chestshop.commands;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.chestshop.services.GiveService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

class GiveCommandTest {

    @Test
    void confirmsWithTheDisplayNameNotTheStoredCode() {
        ItemService items = mock(ItemService.class);
        GiveService give = mock(GiveService.class);
        Message message = mock(Message.class);
        InventoryService inventory = mock(InventoryService.class);
        CommandSender sender = mock(CommandSender.class);
        Player receiver = mock(Player.class);
        PlayerInventory receiverInventory = mock(PlayerInventory.class);
        ItemStack book = mock(ItemStack.class);
        when(receiver.getPlayer()).thenReturn(receiver);
        when(receiver.getName()).thenReturn("Bob");
        when(receiver.getInventory()).thenReturn(receiverInventory);
        when(give.resolveGift("ItTome", 1)).thenReturn(book);
        when(items.getDisplayName(book)).thenReturn("Bk Sharpness V");

        new GiveCommand(items, give, message, inventory).give(sender, "ItTome", 1, receiver);

        verify(inventory).add(book, receiverInventory);
        verify(message).send(sender, "chestshop.ITEM_GIVEN", "prefix", "", "item", "Bk Sharpness V", "player", "Bob");
        verify(items, never()).getName(book);
    }
}
