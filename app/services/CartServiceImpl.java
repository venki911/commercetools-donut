package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import exceptions.PlanVariantNotFoundException;
import io.sphere.sdk.carts.Cart;
import io.sphere.sdk.carts.CartDraft;
import io.sphere.sdk.carts.commands.CartCreateCommand;
import io.sphere.sdk.carts.commands.CartDeleteCommand;
import io.sphere.sdk.carts.commands.CartUpdateCommand;
import io.sphere.sdk.carts.commands.updateactions.AddLineItem;
import io.sphere.sdk.carts.commands.updateactions.RemoveLineItem;
import io.sphere.sdk.carts.commands.updateactions.SetCustomField;
import io.sphere.sdk.carts.commands.updateactions.SetShippingAddress;
import io.sphere.sdk.carts.queries.CartByIdGet;
import io.sphere.sdk.client.PlayJavaSphereClient;
import io.sphere.sdk.commands.UpdateAction;
import io.sphere.sdk.customobjects.CustomObject;
import io.sphere.sdk.customobjects.queries.CustomObjectByKeyGet;
import io.sphere.sdk.models.Address;
import io.sphere.sdk.models.AddressBuilder;
import io.sphere.sdk.models.DefaultCurrencyUnits;
import io.sphere.sdk.products.ByIdVariantIdentifier;
import io.sphere.sdk.products.ProductProjection;
import io.sphere.sdk.products.ProductVariant;
import io.sphere.sdk.products.VariantIdentifier;
import io.sphere.sdk.products.attributes.Attribute;
import io.sphere.sdk.products.attributes.AttributeAccess;
import io.sphere.sdk.types.CustomFieldsDraft;
import pactas.models.PactasContract;
import pactas.models.PactasCustomer;
import play.Logger;
import play.libs.F;
import play.mvc.Http;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@Singleton
public class CartServiceImpl extends AbstractShopService implements CartService {

    private static final Logger.ALogger LOG = Logger.of(CartServiceImpl.class);
    private static final String FREQUENCY_TYPE_KEY = "cart-frequency-key";
    private static final String FREQUENCY_FIELD_KEY = "frequency";

    @Inject
    public CartServiceImpl(final PlayJavaSphereClient playJavaSphereClient) {
        super(playJavaSphereClient);
    }

    @Override
    public F.Promise<Cart> getOrCreateCart(final Http.Session session) {
        requireNonNull(session);
        return Optional.ofNullable(session.get(SessionKeys.CART_ID))
                .map(cardId -> playJavaSphereClient().execute(CartByIdGet.of(cardId)).map(cart -> {
                    LOG.debug("Fetched existing Cart[cartId={}, items={}, custom frequency={}]",
                            cart.getId(), cart.getLineItems().size(), getFrequencyString(cart));
                    return cart;
                }))
                .orElseGet(() -> {
                    final CartDraft cartDraft = CartDraft.of(DefaultCurrencyUnits.EUR)
                            .withCustom(CustomFieldsDraft.ofTypeKeyAndObjects(FREQUENCY_TYPE_KEY, frequencyType(0)));
                    return playJavaSphereClient().execute(CartCreateCommand.of(cartDraft))
                            .map(cart -> {
                                LOG.debug("Created new Cart[cartId={}, items={}, custom frequency={}]",
                                        cart.getId(), cart.getLineItems().size(), getFrequencyString(cart));
                                return cart;
                            });
                });
    }

    private String getFrequencyString(final Cart cart) {
        return Optional.ofNullable(cart.getCustom())
                .map(customFields -> customFields.getFieldAsString(FREQUENCY_FIELD_KEY))
                .orElse("null");
    }

    private static Map<String, Object> frequencyType(final int frequency) {
        return Collections.unmodifiableMap(new HashMap<String, String>() {
            {
                put(FREQUENCY_FIELD_KEY, String.valueOf(frequency));
            }
        });
    }

    @Override
    public F.Promise<Cart> clearCart(final Cart cart) {
        requireNonNull(cart);

        final List<? extends UpdateAction<Cart>> items = cart.getLineItems().stream()
                .map((item) -> RemoveLineItem.of(item))
                .collect(Collectors.toList());

        final F.Promise<Cart> clearedItemsPromise = playJavaSphereClient().execute(CartUpdateCommand.of(cart, items));
        return clearedItemsPromise.flatMap(clearedItemsCart ->
                playJavaSphereClient().execute(CartUpdateCommand.of(clearedItemsCart,
                        SetCustomField.ofObject(FREQUENCY_FIELD_KEY, "0")))
                        .map(clearedTypeCart -> {
                            LOG.debug("Cleared Cart: items={}, custom frequency={}",
                                    clearedTypeCart.getLineItems().size(), getFrequencyString(clearedTypeCart));
                            return clearedTypeCart;
                        }));
    }

    @Override
    public F.Promise<Cart> setProductToCart(final Cart cart, final ByIdVariantIdentifier variantIdentifier, int frequency) {
        requireNonNull(cart);
        requireNonNull(variantIdentifier);
        requireNonNull(frequency);

        final List<? extends UpdateAction<Cart>> cartUpdateActions = Arrays.asList(
                SetCustomField.ofObject(FREQUENCY_FIELD_KEY, String.valueOf(frequency)),
                AddLineItem.of(variantIdentifier.getProductId(), variantIdentifier.getVariantId(), frequency)
        );

        return playJavaSphereClient().execute(CartUpdateCommand.of(cart, cartUpdateActions)).map(updatedCart -> {
            LOG.debug("Updated Cart: items={}, custom frequency={}", updatedCart.getLineItems().size(),
                    getFrequencyString(updatedCart));
            return cart;
        });
    }

    @Override
    public F.Promise<Cart> deleteCart(final Cart cart) {
        requireNonNull(cart);
        return playJavaSphereClient().execute(CartDeleteCommand.of(cart));
    }

    @Override
    public F.Promise<Integer> getFrequency(final String cartId) {
        requireNonNull(cartId);
        final F.Promise<CustomObject<JsonNode>> customObjectPromise =
                playJavaSphereClient().execute(CustomObjectByKeyGet.ofJsonNode(PactasKeys.FREQUENCY, cartId));
        return customObjectPromise.map(this::extractFrequency);
    }

    private Integer extractFrequency(@Nullable final CustomObject<JsonNode> nullableCustomObject) {
        final int result = Optional.ofNullable(nullableCustomObject)
                .map(customObject -> customObject.getValue().asInt()).orElse(0);
        LOG.debug("Extracted frequency: {}", result);
        return result;
    }

    @Override
    public Optional<ProductVariant> getSelectedVariantFromCart(final Cart cart) {
        requireNonNull(cart);
        return (!cart.getLineItems().isEmpty()) ?
                Optional.ofNullable(cart.getLineItems().get(0).getVariant()) :
                Optional.empty();
    }

    @Override
    public F.Promise<Cart> createCartWithPactasInfo(final ProductProjection product, final PactasContract contract, final PactasCustomer customer) {
        requireNonNull(product);
        requireNonNull(contract);
        requireNonNull(customer);
        final F.Promise<Cart> createdCartPromise = playJavaSphereClient().execute(CartCreateCommand.of(CartDraft.of(DefaultCurrencyUnits.EUR)));
        return createdCartPromise.flatMap(createdCart -> {
            LOG.debug("Created new Cart with Pactas info[cartId={}] with Pactas info", createdCart.getId());
            final ProductVariant variant = getVariantInContract(product, contract);
            final AddLineItem action = AddLineItem.of(product, variant.getId(), 1);
            return playJavaSphereClient().execute(CartUpdateCommand.of(createdCart, action));
        }).flatMap(updatedCart -> {
            final Address address = AddressBuilder.of(customer.getCompleteAddress()).build();
            return playJavaSphereClient().execute(CartUpdateCommand.of(updatedCart, SetShippingAddress.of(address)));
        });
    }

    private ProductVariant getVariantInContract(final ProductProjection product, final PactasContract contract) {
        final String planVariantId = contract.getPlanVariantId();
        return variant(product, planVariantId).orElseThrow(() -> new PlanVariantNotFoundException(planVariantId));
    }

    private Optional<ProductVariant> variant(final ProductProjection product, final String pactasId) {
        requireNonNull(pactasId);
        return product.getAllVariants().stream().map(variant -> {

                    final String monthly = Optional.<Attribute>ofNullable(variant.getAttribute(PactasKeys.ID_MONTHLY))
                            .map(attribute -> attribute.getValue(AttributeAccess.ofString()))
                            .orElseThrow(() -> new RuntimeException(format("Unable to get Attribute '%s'",
                                    PactasKeys.ID_MONTHLY)));

                    final String twoWeeks = Optional.<Attribute>ofNullable(variant.getAttribute(PactasKeys.ID_TWO_WEEKS))
                            .map(attribute -> attribute.getValue(AttributeAccess.ofString()))
                            .orElseThrow(() -> new RuntimeException(format("Unable to get Attribute '%s'",
                                    PactasKeys.ID_TWO_WEEKS)));

                    final String weekly = Optional.<Attribute>ofNullable(variant.getAttribute(PactasKeys.ID_WEEKLY))
                            .map(attribute -> attribute.getValue(AttributeAccess.ofString()))
                            .orElseThrow(() -> new RuntimeException(format("Unable to get Attribute '%s'",
                                    PactasKeys.ID_WEEKLY)));

                    return (pactasId.equals(monthly) || pactasId.equals(twoWeeks) || pactasId.equals(weekly)) ? variant : null;
                }
        ).findFirst();
    }
}