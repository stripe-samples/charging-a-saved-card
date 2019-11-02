<?php
use Slim\Http\Request;
use Slim\Http\Response;
use Stripe\Stripe;

require 'vendor/autoload.php';

$dotenv = Dotenv\Dotenv::create(__DIR__);
$dotenv->load();

require './config.php';

$app = new \Slim\App;

// Instantiate the logger as a dependency
$container = $app->getContainer();
$container['logger'] = function ($c) {
  $settings = $c->get('settings')['logger'];
  $logger = new Monolog\Logger($settings['name']);
  $logger->pushProcessor(new Monolog\Processor\UidProcessor());
  $logger->pushHandler(new Monolog\Handler\StreamHandler(__DIR__ . '/logs/app.log', \Monolog\Logger::DEBUG));
  return $logger;
};

$app->add(function ($request, $response, $next) {
    Stripe::setApiKey(getenv('STRIPE_SECRET_KEY'));
    return $next($request, $response);
});

$app->get('/', function (Request $request, Response $response, array $args) {   
  // Display checkout page
  return $response->write(file_get_contents(getenv('STATIC_DIR') . '/index.html'));
});

function calculateOrderAmount()
{
  // Replace this constant with a calculation of the order's amount
  // Calculate the order total on the server to prevent
  // people from directly manipulating the amount on the client
  return 1400;
}

// An endpoint to charge a saved card
// In your application you may want a cron job / other internal process
$app->post('/charge-card-off-session', function (Request $request, Response $response, array $args) {
    $pub_key = getenv('STRIPE_PUBLISHABLE_KEY');
    $body = json_decode($request->getBody());
  try {
    // You need to attach the PaymentMethod to a Customer in order to reuse
    // Since we are using test cards, create a new Customer to save the card to
    $customer = \Stripe\Customer::create([
      'payment_method' => $body->paymentMethod
    ]);
    
    // List the Customer's PaymentMethods to pick one to pay with
    $payment_methods = \Stripe\PaymentMethod::all([
      'customer' => $customer->id,
      'type' => 'card'
    ]);

    // Create a PaymentIntent with the order amount, currency, and saved payment method ID
    // If authentication is required or the card is declined, Stripe
    // will throw an error
    $payment_intent = \Stripe\PaymentIntent::create([
      'amount' => calculateOrderAmount(),
      'currency' => 'usd',
      'payment_method' => $payment_methods->data[0]->id,
      'customer' => $customer->id,
      'confirm' => true,
      'off_session' => true
    ]);
    
    // Send public key and PaymentIntent details to client
    return $response->withJson(array('succeeded' => true, 'publicKey' => $pub_key, 'clientSecret' => $payment_intent->client_secret));

  } catch (\Stripe\Exception\CardException $err) {
    $error_code = $err->getError()->code;

    if($error_code == 'authentication_required') {
      // Bring the customer back on-session to authenticate the purchase
      // You can do this by sending an email or app notification to let them know
      // the off-session purchase failed
      // Use the PM ID and client_secret to authenticate the purchase
      // without asking your customers to re-enter their details
      return $response->withJson(array(
        'error' => 'authentication_required', 
        'amount' => calculateOrderAmount(), 
        'card'=> $err->getError()->payment_method->card, 
        'paymentMethod' => $err->getError()->payment_method->id, 
        'publicKey' => $pub_key, 
        'clientSecret' => $err->getError()->payment_intent->client_secret
      ));

    } else if ($error_code && $err->getError()->payment_intent != null) {
      // The card was declined for other reasons (e.g. insufficient funds)
      // Bring the customer back on-session to ask them for a new payment method
      return $response->withJson(array(
        'error' => $error_code , 
        'publicKey' => $pub_key, 
        'clientSecret' => $err->getError()->payment_intent->client_secret
      ));
    } else {
      $logger = $this->get('logger');
      $logger->info('Unknown error occurred');
    }
  }
});

$app->post('/webhook', function(Request $request, Response $response) {
    $logger = $this->get('logger');
    $event = $request->getParsedBody();
    // Parse the message body (and check the signature if possible)
    $webhookSecret = getenv('STRIPE_WEBHOOK_SECRET');
    if ($webhookSecret) {
      try {
        $event = \Stripe\Webhook::constructEvent(
          $request->getBody(),
          $request->getHeaderLine('stripe-signature'),
          $webhookSecret
        );
      } catch (\Exception $e) {
        return $response->withJson([ 'error' => $e->getMessage() ])->withStatus(403);
      }
    } else {
      $event = $request->getParsedBody();
    }
    $type = $event['type'];
    $object = $event['data']['object'];
    
    if ($type == 'payment_intent.succeeded') {
      // Fulfill any orders, e-mail receipts, etc
      // To cancel the payment you will need to issue a Refund (https://stripe.com/docs/api/refunds)
      $logger->info('💰 Payment succeeded with payment method ' . $object['payment_method']);
    } else if ($type == 'payment_intent.payment_failed') {
      $logger->info('❌ Payment failed with error ' . $object['last_payment_error']['message']);
    } else if ($type == 'payment_method.attached') {
      $logger->info('💳 Attached ' . $object['id'] . ' to customer');
    }

    return $response->withJson([ 'status' => 'success' ])->withStatus(200);
});

$app->run();
