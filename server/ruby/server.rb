# frozen_string_literal: true

require 'stripe'
require 'sinatra'
require 'dotenv'

# Assumes .env is available in ruby/ directory
Dotenv.load
Stripe.api_key = ENV['STRIPE_SECRET_KEY']

set :static, true
set :public_folder, File.join(File.dirname(__FILE__), ENV['STATIC_DIR'])
set :port, 4242

get '/' do
  # Display checkout page
  content_type 'text/html'
  send_file File.join(settings.public_folder, 'index.html')
end

def calculate_order_amount
  # Replace this constant with a calculation of the order's amount
  # Calculate the order total on the server to prevent
  # people from directly manipulating the amount on the client
  1400
end

# An endpoint to charge a saved card
# In your application you may want a cron job / other internal process
post '/charge-card-off-session' do
  content_type 'application/json'
  data = JSON.parse(request.body.read)

  begin
    # You need to attach the PaymentMethod to a Customer in order to reuse
    # Since we are using test cards, create a new Customer here
    # You would do this in your payment flow that saves cards
    customer = Stripe::Customer.create(
      payment_method: data['paymentMethod']
    )

    # List the customer's payment methods to find one to charge
    payment_methods = Stripe::PaymentMethod.list(
      customer: customer.id,
      type: 'card'
    )

    # Create and confirm a PaymentIntent with the
    # order amount, currency, Customer and PaymentMethod IDs
    # If authentication is required or the card is declined, Stripe
    # will throw an error
    payment_intent = Stripe::PaymentIntent.create(
      amount: calculate_order_amount,
      currency: 'usd',
      payment_method: payment_methods.data[0].id,
      customer: customer.id,
      confirm: true,
      off_session: true
    )

    {
      succeeded: true,
      clientSecret: payment_intent.client_secret,
      publicKey: ENV['STRIPE_PUBLISHABLE_KEY']
    }.to_json
  rescue Stripe::CardError => e
    if e.error.code.eql? 'authentication_required'
      # Bring the customer back on-session to authenticate the purchase
      # You can do this by sending an email or app notification to let them know
      # the off-session purchase failed
      # Use the PM ID and client_secret to authenticate the purchase
      # without asking your customers to re-enter their details
      {
        error: 'authentication_required',
        paymentMethod: e.error.payment_method.id,
        clientSecret: e.error.payment_intent.client_secret,
        publicKey: ENV['STRIPE_PUBLISHABLE_KEY'],
        amount: calculate_order_amount,
        card: e.error.payment_method.card
      }.to_json

    elsif e.error.code
      # The card was declined for other reasons (e.g. insufficient funds)
      # Bring the customer back on-session to ask them for a new payment method
      {
        error: e.error.code,
        clientSecret: e.error.payment_intent.client_secret,
        publicKey: ENV['STRIPE_PUBLISHABLE_KEY']
      }.to_json
    else
      puts 'Unknown error occurred'
    end
  end
end

post '/webhook' do
  # Use webhooks to receive information about asynchronous payment events.
  # For more about our webhook events check out https://stripe.com/docs/webhooks.
  webhook_secret = ENV['STRIPE_WEBHOOK_SECRET']
  payload = request.body.read
  if !webhook_secret.empty?
    # Retrieve the event by verifying the signature using the raw body and secret if webhook signing is configured.
    sig_header = request.env['HTTP_STRIPE_SIGNATURE']
    event = nil

    begin
      event = Stripe::Webhook.construct_event(
        payload, sig_header, webhook_secret
      )
    rescue JSON::ParserError => e
      # Invalid payload
      status 400
      return
    rescue Stripe::SignatureVerificationError => e
      # Invalid signature
      puts '⚠️  Webhook signature verification failed.'
      status 400
      return
    end
  else
    data = JSON.parse(payload, symbolize_names: true)
    event = Stripe::Event.construct_from(data)
  end
  # Get the type of webhook event sent - used to check the status of PaymentIntents.
  event_type = event['type']
  data = event['data']
  data_object = data['object']

  if event_type == 'payment_intent.succeeded'
    puts '💰 Payment succeeded with payment method ' + data_object.payment_method
    # Fulfill any orders, e-mail receipts, etc
    # To cancel the payment you will need to issue a Refund (https://stripe.com/docs/api/refunds)
  elsif event_type == 'payment_intent.payment_failed'
    puts '❌ Payment failed with error: ' + data_object.last_payment_error.message
  elsif event_type == 'payment_method.attached'
    puts '💳 Attached ' + data_object.id + ' to customer'
  end

  content_type 'application/json'
  {
    status: 'success'
  }.to_json
end
