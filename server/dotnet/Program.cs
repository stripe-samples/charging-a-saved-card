using System.Text.Json;
using Microsoft.Extensions.Options;
using Stripe;

DotNetEnv.Env.Load();
StripeConfiguration.ApiKey = Environment.GetEnvironmentVariable("STRIPE_SECRET_KEY");

StripeConfiguration.AppInfo = new AppInfo
{
    Name = "https://github.com/stripe-samples/charging-a-saved-card",
    Url = "https://github.com/stripe-samples",
    Version = "0.1.0",
};

StripeConfiguration.ApiKey = Environment.GetEnvironmentVariable("STRIPE_SECRET_KEY");

var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    WebRootPath = Environment.GetEnvironmentVariable("STATIC_DIR")
});

builder.Services.Configure<StripeOptions>(options =>
{
    options.PublishableKey = Environment.GetEnvironmentVariable("STRIPE_PUBLISHABLE_KEY");
    options.SecretKey = Environment.GetEnvironmentVariable("STRIPE_SECRET_KEY");
    options.WebhookSecret = Environment.GetEnvironmentVariable("STRIPE_WEBHOOK_SECRET");
});

var app = builder.Build();

if (app.Environment.IsDevelopment())
{
    app.UseDeveloperExceptionPage();
}

app.UseDefaultFiles();
app.UseStaticFiles();

app.MapPost("/charge-card-off-session", async (HttpRequest request, IOptions<StripeOptions> stripeConfig) =>
{
    try
    {
        var json = await new StreamReader(request.Body).ReadToEndAsync();
        using var jdocument = JsonDocument.Parse(json);

        var paymentMethod = jdocument.RootElement.GetProperty("paymentMethod").GetString();
        var customerService = new CustomerService();

        // You need to attach the PaymentMethod to a Customer in order to reuse
        // Since we are using test cards, create a new Customer here
        // You would do this in your payment flow that saves cards
        var customer = await customerService.CreateAsync(new CustomerCreateOptions
        {
            PaymentMethod = paymentMethod
        });

        //  List the customer's payment methods to find one to charge
        var paymentMethodService = new PaymentMethodService();
        var customerPaymentMethods = await paymentMethodService.ListAsync(new PaymentMethodListOptions
        {
            Customer = customer.Id,
            Type = "card"
        });

        //  Create and confirm a PaymentIntent with the
        //  order amount, currency, Customer and PaymentMethod IDs
        //  If authentication is required or the card is declined, Stripe
        //  will throw an error
        var paymentIntentService = new PaymentIntentService();
        var paymentIntent = await paymentIntentService.CreateAsync(new PaymentIntentCreateOptions
        {
            Customer = customer.Id,
            Amount = 1400,
            Currency = "usd",
            PaymentMethod = customerPaymentMethods.First().Id,
            Confirm = true,
            OffSession = true
        });

        return Results.Ok(new
        {
            Succeeded = true,
            PublicKey = stripeConfig.Value.PublishableKey,
            ClientSecret = paymentIntent.ClientSecret
        });
    }
    catch (StripeException ex)
    {
        if (ex.StripeError.Type == "card_error" && ex.StripeError.Code == "authentication_required")
        {
            return Results.Json(new
            {
                Error = ex.StripeError.Code,
                PaymentMethod = ex.StripeError.PaymentMethod.Id,
                Amount = 1400,
                Card = ex.StripeError.PaymentMethod.Card,
                PublicKey = stripeConfig.Value.PublishableKey,
                ClientSecret = ex.StripeError.PaymentIntent.ClientSecret
            });
        }
        return Results.Json(new
        {
            Error = ex.StripeError.Code,
            PublicKey = stripeConfig.Value.PublishableKey,
            ClientSecret = ex.StripeError.PaymentIntent.ClientSecret
        });
    }
});

app.MapPost("/webhook", async (HttpRequest request, IOptions<StripeOptions> options) =>
{
    var json = await new StreamReader(request.Body).ReadToEndAsync();
    Event stripeEvent;
    try
    {
        stripeEvent = EventUtility.ConstructEvent(
            json,
            request.Headers["Stripe-Signature"],
             options.Value.WebhookSecret
        );
        app.Logger.LogInformation($"Webhook notification with type: {stripeEvent.Type} found for {stripeEvent.Id}");
        
        if (stripeEvent.Type == "payment_intent.succeeded")
        {
            var intentData = stripeEvent.Data.Object as PaymentIntent;
            app.Logger.LogInformation("💰 Payment succeeded with payment method {PaymentMethod}", intentData.PaymentMethod.Id);
            // Fulfill any orders, e-mail receipts, etc
            // To cancel the payment you will need to issue a Refund (https://stripe.com/docs/api/refunds)
        }
        else if (stripeEvent.Type == "payment_intent.payment_failed")
        {
            var intentData = stripeEvent.Data.Object as PaymentIntent;
            app.Logger.LogError("❌ Payment failed with error: {PaymenErrorMessage}", intentData.LastPaymentError.Message);
        }
        else if (stripeEvent.Type == "payment_method.attached")
        {
            var paymentData = stripeEvent.Data.Object as PaymentMethod;
            app.Logger.LogInformation("💳 Attached {PaymentMethodId} to customer", paymentData.Id);
        }
    }
    catch (Exception e)
    {
        app.Logger.LogInformation($"Something failed {e}");
        return Results.BadRequest();
    }

    return Results.Ok();
});

app.Run();