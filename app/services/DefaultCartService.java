package services;

import io.sphere.sdk.carts.Cart;
import io.sphere.sdk.carts.CartDraft;
import io.sphere.sdk.carts.commands.CartCreateCommand;
import io.sphere.sdk.carts.commands.CartUpdateCommand;
import io.sphere.sdk.carts.commands.updateactions.RemoveLineItem;
import io.sphere.sdk.carts.queries.CartByIdGet;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.models.DefaultCurrencyUnits;
import io.sphere.sdk.products.ProductProjection;
import io.sphere.sdk.products.queries.ProductProjectionQuery;
import io.sphere.sdk.queries.PagedQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static org.springframework.util.Assert.notNull;

public final class DefaultCartService implements CartService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCartService.class);
    private final SphereClient sphereClient;

    public DefaultCartService(final SphereClient sphereClient) {
        this.sphereClient = sphereClient;
    }

    public Cart createOrGet(final HttpSession session) {
        notNull(session, "Session is null, unable to create or get Cart");
        return Optional.ofNullable(session.getAttribute(SessionKeys.CART_ID))
                .map(String::valueOf)
                .map(cardId -> {
                            final Cart cart = sphereClient.execute(CartByIdGet.of(cardId)).toCompletableFuture().join();
                            LOG.debug("Fetched existing Cart[cartId={}]", cart.getId());
                            return cart;
                        }
                )
                .orElseGet(() -> {
                    final Cart cart = sphereClient.execute(CartCreateCommand.of(CartDraft.of(DefaultCurrencyUnits.EUR))).toCompletableFuture().join();
                    LOG.debug("Created new Cart[cartId={}]", cart.getId());
                    session.setAttribute(SessionKeys.CART_ID, cart.getId());
                    return cart;
                });
    }

    @Override
    public Cart clearCart(final Cart cart) {
        final List<RemoveLineItem> items = cart.getLineItems().stream().map((item) -> {
            final RemoveLineItem removeLineItem = RemoveLineItem.of(item, 1);
            return removeLineItem;
        }).collect(Collectors.toList());
        final Cart result = sphereClient.execute(CartUpdateCommand.of(cart, items)).toCompletableFuture().join();
        return result;
    }

    @Override
    public Optional<ProductProjection> getProduct() {
        final ProductProjectionQuery request = ProductProjectionQuery.ofCurrent();
        final CompletionStage<PagedQueryResult<ProductProjection>> resultCompletionStage =
                sphereClient.execute(request);
        final PagedQueryResult<ProductProjection> queryResult = resultCompletionStage.toCompletableFuture().join();
        return queryResult.head();
    }
}
