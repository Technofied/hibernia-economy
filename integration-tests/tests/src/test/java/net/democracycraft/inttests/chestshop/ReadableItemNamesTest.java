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

    @Test
    void giveTakesAOneWordShorthandAndConfirmsWithTheReadableName() {
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("Rng"))) {
            String name = admin.getUsername();
            Perms.grant(name, "chestshop.admin");
            Inv.clear(name);
            admin.drainMessages();
            admin.chatCommand("/chestshop give Bk_Sharpness_V 1");
            assertThat(admin.awaitMessageContaining("Given", TIMEOUT))
                    .contains(NAME)
                    .doesNotContain("#");
            Inv.waitForPlayerCount(name, "ENCHANTED_BOOK", 1, TIMEOUT);
            assertThat(RconClient.send("execute if items entity " + name + " container.* " + SHARPNESS_BOOK
                    + " run data get entity " + name + " SelectedItemSlot"))
                    .contains("has the following entity data");
        }
    }

    /**
     * A sign that reads "Bk Sharpness V" carries no stored code, so a denial written against
     * the code has to be found from the traded item. PAR-568 introduced such signs.
     */
    @Test
    void anItemSpecificBuyDenialAppliesAtAShopWhoseSignShowsTheReadableName() {
        String seller = Usernames.fresh("Rds");
        String buyer = Usernames.fresh("Rdb");

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

            // The stored code, as an operator would read it from /iteminfo.
            Perms.grant(seller, "chestshop.iteminfo");
            RconClient.send("item replace entity " + seller + " weapon.mainhand with " + SHARPNESS_BOOK);
            Barrier.settle();
            s.drainMessages();
            s.chatCommand("/iteminfo");
            String reply = s.awaitMessageContaining("Full Name:", TIMEOUT);
            String code = reply.substring(reply.indexOf("Full Name:") + "Full Name:".length()).strip();
            assertThat(code).startsWith("Enchanted Book#");

            // The same code as a sign would show it: without the space when it is too wide.
            String node = "chestshop.shop.buy." + code.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
            RconClient.send("lp user " + buyer + " permission set " + node + " false");
            Await.until("item denial applied", () -> Perms.check(buyer, node),
                    "has=false set=true"::equals, TIMEOUT);

            shop.clickBuy(b, buyer);
            Barrier.settle();

            assertThat(Ledger.personalBalance(b.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Inv.playerCount(buyer, "ENCHANTED_BOOK")).isZero();
            assertThat(Inv.chestCount(shop.chestPos(), "ENCHANTED_BOOK")).isEqualTo(1);
        }
    }

    /** Shops recorded before PAR-569 pick up the readable name the next time they are scanned. */
    @Test
    void aShopRecordedUnderItsOldNameIsRenamedWhenItIsNextScanned() throws Exception {
        String seller = Usernames.fresh("Rnr");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Perms.grant(seller, "chestshop.admin");
            assertThat(shop.createSellShopRaw(seller, seller, 1, PRICE, "bk sharpness v"))
                    .contains("cancelled=false");
            Await.until("shop registered", () -> shopItemName(shop), NAME::equals, TIMEOUT);

            // What the registry held for every enchanted book before this change.
            try (var c = java.sql.DriverManager.getConnection(
                            net.democracycraft.inttests.support.DevServer.DB_URL,
                            net.democracycraft.inttests.support.DevServer.DB_USER,
                            net.democracycraft.inttests.support.DevServer.DB_PASSWORD);
                 var ps = c.prepareStatement("UPDATE chestshop_shop SET item_name = 'Enchanted Book' "
                         + "WHERE sign_x = ? AND sign_y = ? AND sign_z = ?")) {
                ps.setInt(1, shop.signPos().x());
                ps.setInt(2, shop.signPos().y());
                ps.setInt(3, shop.signPos().z());
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            s.chatCommand("/chestshop resync 10");

            Await.until("shop renamed by the scan", () -> shopItemName(shop), NAME::equals, Duration.ofSeconds(30));
        }
    }

    private static String shopItemName(ChestShopFixture shop) {
        try (var c = java.sql.DriverManager.getConnection(
                        net.democracycraft.inttests.support.DevServer.DB_URL,
                        net.democracycraft.inttests.support.DevServer.DB_USER,
                        net.democracycraft.inttests.support.DevServer.DB_PASSWORD);
             var ps = c.prepareStatement(
                     "SELECT item_name FROM chestshop_shop WHERE sign_x = ? AND sign_y = ? AND sign_z = ?")) {
            ps.setInt(1, shop.signPos().x());
            ps.setInt(2, shop.signPos().y());
            ps.setInt(3, shop.signPos().z());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
