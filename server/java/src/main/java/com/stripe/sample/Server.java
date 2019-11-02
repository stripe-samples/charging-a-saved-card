package com.stripe.sample;

import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.staticFiles;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.stripe.Stripe;
import com.stripe.exception.CardException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.PaymentMethodCollection;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodListParams;

import io.github.cdimascio.dotenv.Dotenv;

public class Server {
    private static Gson gson = new Gson();

    static class CreatePaymentBody {
        @SerializedName("paymentMethod")
        String paymentMethod;

        public String getPaymentMethod() {
            return paymentMethod;
        }
    }

    static class RequiresAuthenticationResponse {
        private String publicKey;
        private String clientSecret;
        private String paymentMethod;
        private String error;
        private int amount;
        private PaymentMethod.Card card;

        public RequiresAuthenticationResponse(String publicKey, String clientSecret, String paymentMethod, String error,
                int amount, PaymentMethod.Card card) {
            this.publicKey = publicKey;
            this.clientSecret = clientSecret;
            this.paymentMethod = paymentMethod;
            this.error = error;
            this.amount = amount;
            this.card = card;
        }
    }

    static class RequiresNewPaymentMethodResponse {
        private String publicKey;
        private String clientSecret;
        private String error;

        public RequiresNewPaymentMethodResponse(String publicKey, String clientSecret, String error) {
            this.publicKey = publicKey;
            this.clientSecret = clientSecret;
            this.error = error;
        }
    }

    static class PaymentSucceededResponse {
        private String publicKey;
        private String clientSecret;
        private Boolean succeeded;

        public PaymentSucceededResponse(String publicKey, String clientSecret, Boolean succeeded) {
            this.publicKey = publicKey;
            this.clientSecret = clientSecret;
            this.succeeded = succeeded;
        }
    }

    static int calculateOrderAmount() {
        // Replace this constant with a calculation of the order's amount
        // Calculate the order total on the server to prevent
        // users from directly manipulating the amount on the client
        return 1400;
    }

    public static void main(String[] args) {
        port(4242);
        Dotenv dotenv = Dotenv.load();
        Stripe.apiKey = dotenv.get("STRIPE_SECRET_KEY");

        staticFiles.externalLocation(
                Paths.get(Paths.get("").toAbsolutePath().toString(), dotenv.get("STATIC_DIR")).normalize().toString());

        // An endpoint to charge a saved card
        // In your application you may want a cron job / other internal process
        post("/charge-card-off-session", (request, response) -> {
            response.type("application/json");
            CreatePaymentBody postBody = null;
            PaymentIntent paymentIntent = null;

            try {
                postBody = gson.fromJson(request.body(), CreatePaymentBody.class);

                // You need to attach the PaymentMethod to a Customer in order to reuse
                // Since we are using test cards, create a new Customer here
                // You would do this in your payment flow that saves cards
                CustomerCreateParams customerCreateParams = new CustomerCreateParams.Builder()
                        .setPaymentMethod(postBody.getPaymentMethod()).build();
                Customer customer = Customer.create(customerCreateParams);

                // List the customer's payment methods to find one to charge
                PaymentMethodListParams listParams = new PaymentMethodListParams.Builder().setCustomer(customer.getId())
                        .setType(PaymentMethodListParams.Type.CARD).build();

                PaymentMethodCollection paymentMethods = PaymentMethod.list(listParams);

                PaymentIntentCreateParams createParams = new PaymentIntentCreateParams.Builder().setCurrency("usd")
                        .setAmount(new Long(calculateOrderAmount()))
                        .setPaymentMethod(paymentMethods.getData().get(0).getId()).setCustomer(customer.getId())
                        .setConfirm(true).setOffSession(true).build();
                // Create and confirm a PaymentIntent with the order amount, currency, 
                // Customer and PaymentMethod ID
                // If authentication is required or the card is declined, Stripe
                // will throw an error
                paymentIntent = PaymentIntent.create(createParams);
            } catch (CardException err) {
                if (err.getCode().equals("authentication_required")) {
                    // Bring the customer back on-session to authenticate the purchase
                    // You can do this by sending an email or app notification to let them know
                    // the off-session purchase failed
                    // Use the PM ID and client_secret to authenticate the purchase
                    // without asking your customers to re-enter their details
                    return gson.toJson(new RequiresAuthenticationResponse(dotenv.get("STRIPE_PUBLISHABLE_KEY"),
                            err.getStripeError().getPaymentIntent().getClientSecret(),
                            err.getStripeError().getPaymentMethod().getId(), "authentication_required",
                            calculateOrderAmount(), err.getStripeError().getPaymentMethod().getCard()));
                } else if (err.getCode() != null) {
                    // The card was declined for other reasons (e.g. insufficient funds)
                    // Bring the customer back on-session to ask them for a new payment method
                    return gson.toJson(new RequiresNewPaymentMethodResponse(dotenv.get("STRIPE_PUBLISHABLE_KEY"),
                            err.getStripeError().getPaymentIntent().getClientSecret(), err.getCode()));
                } else {
                    System.out.println("Unknown error occurred");
                }
            }
            // Otherwise PaymentIntent succeeded, no recovery flow needed
            return gson.toJson(new PaymentSucceededResponse(dotenv.get("STRIPE_PUBLISHABLE_KEY"),
                    paymentIntent.getClientSecret(), true));

        });

        post("/webhook", (request, response) -> {
            String payload = request.body();
            String sigHeader = request.headers("Stripe-Signature");
            String endpointSecret = dotenv.get("STRIPE_WEBHOOK_SECRET");

            Event event = null;

            try {
                event = Webhook.constructEvent(payload, sigHeader, endpointSecret);
            } catch (SignatureVerificationException e) {
                // Invalid signature
                response.status(400);
                return "";
            }

            EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
            StripeObject stripeObject = null;
            if (dataObjectDeserializer.getObject().isPresent()) {
                stripeObject = dataObjectDeserializer.getObject().get();
            } else {
                // Deserialization failed, probably due to an API version mismatch.
                // Refer to the Javadoc documentation on `EventDataObjectDeserializer` for
                // instructions on how to handle this case, or return an error here.
            }

            switch (event.getType()) {
            case "payment_intent.succeeded":
                // The payment was complete
                // Fulfill any orders, e-mail receipts, etc
                PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
                System.out.println("💰 Payment succeeded with payment method " + paymentIntent.getPaymentMethod());
                break;
            case "payment_intent.payment_failed":
                // The payment failed to go through due to decline or authentication request
                PaymentIntent failedPaymentIntent = (PaymentIntent) stripeObject;
                System.out.println(
                        "❌ Payment failed with error: " + failedPaymentIntent.getLastPaymentError().getMessage());
                break;
            case "payment_method.attached":
                // A new payment method was attached to a customer
                PaymentMethod paymentMethod = (PaymentMethod) stripeObject;
                System.out.println("💳 Attached " + paymentMethod.getId() + " to customer");
            default:
                // Unexpected event type
                response.status(400);
                return "";
            }

            response.status(200);
            return "";
        });
    }
}