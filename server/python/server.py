#! /usr/bin/env python3.6

"""
server.py
Stripe Sample.
Python 3.6 or newer required.
"""

import stripe
import json
import os

from flask import Flask, render_template, jsonify, request, send_from_directory
from dotenv import load_dotenv, find_dotenv

# Setup Stripe python client library
load_dotenv(find_dotenv())
stripe.api_key = os.getenv('STRIPE_SECRET_KEY')
stripe.api_version = os.getenv('STRIPE_API_VERSION')

static_dir = str(os.path.abspath(os.path.join(
    __file__, "..", os.getenv("STATIC_DIR"))))
app = Flask(__name__, static_folder=static_dir,
            static_url_path="", template_folder=static_dir)


@app.route('/', methods=['GET'])
def get_checkout_page():
    # Display checkout page
    return render_template('index.html')


def calculate_order_amount():
    # Replace this constant with a calculation of the order's amount
    # Calculate the order total on the server to prevent
    # people from directly manipulating the amount on the client
    return 1400


@app.route('/charge-card-off-session', methods=['POST'])
def create_payment():
    data = json.loads(request.data)
    try:
        # You need to attach the PaymentMethod to a Customer in order to reuse
        # Since we are using test cards, create a new Customer here
        # You would do this in your payment flow that saves cards
        customer = stripe.Customer.create(
            payment_method=data['paymentMethod']
        )

        # List the customer's payment methods to find one to charge
        payment_methods = stripe.PaymentMethod.list(
            customer=customer['id'],
            type='card'
        )

        # Create and confirm a PaymentIntent with the
        # order amount, currency, Customer and PaymentMethod IDs
        # If authentication is required or the card is declined, Stripe
        # will throw an error
        intent = stripe.PaymentIntent.create(
            amount=calculate_order_amount(),
            currency='usd',
            payment_method=payment_methods['data'][0]['id'],
            customer=customer['id'],
            confirm=True,
            off_session=True
        )

        return jsonify({
            'succeeded': True, 
            'publicKey': os.getenv('STRIPE_PUBLISHABLE_KEY'), 
            'clientSecret': intent.client_secret
        })
    except stripe.error.CardError as e:
        err = e.error
        if err.code == 'authentication_required':
            # Bring the customer back on-session to authenticate the purchase
            # You can do this by sending an email or app notification to let them know
            # the off-session purchase failed
            # Use the PM ID and client_secret to authenticate the purchase
            # without asking your customers to re-enter their details
            return jsonify({
                'error': 'authentication_required', 
                'paymentMethod': err.payment_method.id, 
                'amount': calculate_order_amount(), 
                'card': err.payment_method.card, 
                'publicKey': os.getenv('STRIPE_PUBLISHABLE_KEY'), 
                'clientSecret': err.payment_intent.client_secret
            })
        elif err.code:
            # The card was declined for other reasons (e.g. insufficient funds)
            # Bring the customer back on-session to ask them for a new payment method
            return jsonify({
                'error': err.code, 
                'publicKey': os.getenv('STRIPE_PUBLISHABLE_KEY'), 
                'clientSecret': err.payment_intent.client_secret
            })

@app.route('/webhook', methods=['POST'])
def webhook_received():
    # You can use webhooks to receive information about asynchronous payment events.
    # For more about our webhook events check out https://stripe.com/docs/webhooks.
    webhook_secret = os.getenv('STRIPE_WEBHOOK_SECRET')
    request_data = json.loads(request.data)

    if webhook_secret:
        # Retrieve the event by verifying the signature using the raw body and secret if webhook signing is configured.
        signature = request.headers.get('stripe-signature')
        try:
            event = stripe.Webhook.construct_event(
                payload=request.data, sig_header=signature, secret=webhook_secret)
            data = event['data']
        except Exception as e:
            return e
        # Get the type of webhook event sent - used to check the status of PaymentIntents.
        event_type = event['type']
    else:
        data = request_data['data']
        event_type = request_data['type']
    data_object = data['object']

    if event_type == 'payment_intent.succeeded':
        print('💰 Payment succeeded with payment method ' + data_object['payment_method'])
        # Fulfill any orders, e-mail receipts, etc
        # To cancel the payment you will need to issue a Refund (https://stripe.com/docs/api/refunds)
    elif event_type == 'payment_intent.payment_failed':
        print('❌ Payment failed with error: ' + data_object['last_payment_error']['message'])
    elif event_type == 'payment_method.attached':
        print( '💳 Attached ' + data_object['id'] + ' to customer')
    return jsonify({'status': 'success'})


if __name__ == '__main__':
    app.run()
