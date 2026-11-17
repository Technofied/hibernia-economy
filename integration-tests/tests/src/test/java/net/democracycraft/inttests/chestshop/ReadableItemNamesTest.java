package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Dialogs;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Market;
import net.democracycraft.inttests.support.Perms;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAR-569: a potion or enchanted book is shown by its readable name everywhere a person sees
 * it, not only on the sign, while the stored code stays the item's identity in the registry.
 */
class ReadableItemNamesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int PRICE = 250;
    private static final String NAME = "Bk Sharpness V";
    private static final String SHARPNESS_BOOK = "minecraft:enchanted_book[stored_enchantments={sharpness:5}]";

    @Test
    void aSaleShowsTheReadableNameAndRecordsTheStoredCodeAsItsKey() {
        String seller = Usernames.fresh("Rns");
        String buyer = Usernames.fresh("Rnb");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            assertThat(shop.createSellShopRaw(seller, seller, 1, PRICE, "bk sharpness v"))
                    .contains("cancelled=false");
            RconClient.send(String.format("item replace block %d %d %d container.0 with %s",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z(), SHARPNESS_BOOK));
            Inv.clear(buyer);
            RconClient.send("diag warmname " + seller);

            // Shop information.
            Perms.grant(seller, "chestshop.shopinfo");
            shop.positionClicker(s, seller);
            s.drainMessages();
            s.chatCommand("/shopinfo");
            assertThat(s.awaitMessageContaining("Shop Information", TIMEOUT))
                    .contains(NAME)
                    .doesNotContain("#");

            // The buyer's trade message.
            long checkpoint = Ledger.transactionCheckpoint();
            b.drainMessages();
            shop.clickBuy(b, buyer);
            assertThat(b.awaitMessageContaining("You bought", TIMEOUT))
                    .contains(NAME)
                    .doesNotContain("#");
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);

            // The registry: readable name for people, stored code as the identity.
            Market.Sale sale = Await.until("sale recorded",
                            () -> Market.latestSaleForCustomer(b.getUuid()), Optional::isPresent, TIMEOUT)
                    .orElseThrow();
            assertThat(sale.itemName()).isEqualTo(NAME);
            assertThat(sale.itemKey()).startsWith("Enchanted Book#");

            // The ledger memo.
            assertThat(Ledger.initiatedPostingsAfter(b.getUuid(), checkpoint))
                    .anySatisfy(posting -> assertThat(posting.message())
                            .contains(buyer + " bought x1 " + NAME + " from " + seller));
        }
    }

    @Test
    void findTitlesItsSearchWithTheReadableName() {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Rnf"))) {
            String name = player.getUsername();
            Perms.grant(name, "chestshop.find");
            Perms.grant(name, "chestshop.iteminfo");
            RconClient.send("item replace entity " + name + " hotbar.0 with " + SHARPNESS_BOOK);
            player.selectHotbarSlot(0);
            Barrier.sync(player);

            // Searching for the held item.
            var held = Dialogs.await(player, () -> player.chatCommand("/find"), d -> true);
            assertThat(Dialogs.title(held)).contains("Find Shops", NAME).doesNotContain("#");

            // Searching by the stored code, as an old tab-completion would supply it.
            player.drainMessages();
            player.chatCommand("/iteminfo");
            String reply = player.awaitMessageContaining("Full Name:", TIMEOUT);
            String code = reply.substring(reply.indexOf("Full Name:") + "Full Name:".length()).strip();
            assertThat(code).startsWith("Enchanted Book#");
            var typed = Dialogs.await(player, () -> player.chatCommand("/find " + code), d -> true);
            assertThat(Dialogs.title(typed)).contains("Find Shops", NAME).doesNotContain("#");
        }
    }
}
