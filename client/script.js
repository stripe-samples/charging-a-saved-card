// A reference to Stripe.js
var stripe;
// A reference to the card details element on your page
var card;

/*
 * Makes a request to the server to charge a previously saved card off-session
 * We will use test cards from https://stripe.com/docs/testing
 */
var chargeCard = function(paymentMethod) {
  fetch("/charge-card-off-session", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ paymentMethod: paymentMethod })
  })
    .then(function(result) {
      return result.json();
    })
    .then(function(data) {
      // Setup Stripe elements to collect payment method details
      setupElements(data.publicKey);
      // Setup event handlers
      setupAuthenticationView(data.clientSecret, data.paymentMethod);
      setupNewPaymentMethodView(data.clientSecret);
      hideEl(".sr-select-pm");

      if (data.error && data.error === "authentication_required") {
        // Card needs to be authenticatied
        // Reuse the card details we have to use confirmCardPayment() to prompt for authentication
        showAuthenticationView(data);
      } else if (data.error) {
        // Card was declined off-session -- ask customer for a new card
        showEl(".requires-pm");
      } else if (data.succeeded) {
        // Card was successfully charged off-session
        // No recovery flow needed
        paymentIntentSucceeded(data.clientSecret, ".sr-select-pm");
      }
    });
};

// Set up Stripe Elements to collect card details if needed
var setupElements = function(publicKey) {
  stripe = Stripe(publicKey);
  var elements = stripe.elements();
  var style = {
    base: {
      color: "#32325d",
      fontFamily: '"Helvetica Neue", Helvetica, sans-serif',
      fontSmoothing: "antialiased",
      fontSize: "16px",
      "::placeholder": {
        color: "#aab7c4"
      }
    },
    invalid: {
      color: "#fa755a",
      iconColor: "#fa755a"
    }
  };

  card = elements.create("card", { style: style });
  card.mount("#card-element");
};

document.querySelector("form").addEventListener("submit", function(evt) {
  evt.preventDefault();
  changeLoadingState(true, "#submit");
  var selectedPaymentMethod = document.querySelector("option:checked").value;
  chargeCard(selectedPaymentMethod);
});

// Show view to request card authentication
var showAuthenticationView = function(result) {
  var amountInUSD = Number(result.amount / 100).toLocaleString("en-US", {
    style: "currency",
    currency: "usd"
  });
  var cardBrand = result.card.brand
    ? result.card.brand.charAt(0).toUpperCase() + result.card.brand.slice(1)
    : "";
  showEl(".requires-auth");
  updateTextContent("#card-brand", cardBrand);
  updateTextContent("#last4", result.card.last4);
  updateTextContent("#order-amount", amountInUSD);
};

// Set up event handler for authentication view
var setupAuthenticationView = function(clientSecret, paymentMethod) {
  // Event handler to prompt a customer to authenticate a previously provided card
  document
    .querySelector("#authenticate")
    .addEventListener("click", function(evt) {
      changeLoadingState(true, "#authenticate");

      // Use confirmCardPayment() to ask the customer to authenticate
      // a previously saved card
      stripe
        .confirmCardPayment(clientSecret, {
          payment_method: paymentMethod
        })
        .then(function(stripeJsResult) {
          changeLoadingState(false, "#authenticate");
          if (
            stripeJsResult.error &&
            stripeJsResult.error.code ===
              "payment_intent_authentication_failure"
          ) {
            // Authentication failed -- prompt for a new payment method since this one is failing to authenticate
            hideEl(".requires-auth");
            showEl(".requires-pm");
          } else if (
            stripeJsResult.paymentIntent &&
            stripeJsResult.paymentIntent.status === "succeeded"
          ) {
            // Order was authenticated and the card was charged
            // There's a risk your customer will drop-off or close the browser before this callback executes
            // We recommend handling any business-critical post-payment logic in a webhook
            paymentIntentSucceeded(clientSecret, ".requires-auth");
          }
        });
    });
};

// Set up event handler for new payment method view
var setupNewPaymentMethodView = function(clientSecret) {
  // Event handler to prompt a customer to enter new payment details
  document.querySelector("#update-pm").addEventListener("click", function(evt) {
    changeLoadingState(true, "#update-pm");
    // Use confirmCardPayment() to attemp to pay for the PaymentIntent with a
    // new card (collected by the Card Element) and save it to the customer
    stripe
      .confirmCardPayment(clientSecret, {
        payment_method: { card: card },
        save_payment_method: true
      })
      .then(function(stripeJsResult) {
        changeLoadingState(false, "#update-pm");
        if (stripeJsResult.error) {
          // Ask for new card details
          showError(stripeJsResult.error.message);
        } else if (
          stripeJsResult.paymentIntent &&
          stripeJsResult.paymentIntent.status === "succeeded"
        ) {
          // New card details were used to pay for the PaymentIntent
          // There's a risk your customer will drop-off or close the browser before this callback executes
          // We recommend handling any business-critical post-payment logic in a webhook
          paymentIntentSucceeded(clientSecret, ".requires-pm");
        }
      });
  });
};

/* ------- UI helpers ------- */
var showEl = function(selector) {
  document.querySelector(selector).classList.remove("hidden");
};

var hideEl = function(selector) {
  document.querySelector(selector).classList.add("hidden");
};

var updateTextContent = function(selector, text) {
  document.querySelector(selector).textContent = text;
};

var showError = function(errorMsgText) {
  var errorMsg = document.querySelector(".sr-field-error");
  errorMsg.textContent = errorMsgText;
  setTimeout(function() {
    errorMsg.textContent = "";
  }, 4000);
};

// Show a spinner on button click
var changeLoadingState = function(isLoading, selector) {
  if (isLoading) {
    document.querySelector(selector).disabled = true;
    document.querySelector(selector + " .spinner").classList.remove("hidden");
    document.querySelector(selector + " .button-text").classList.add("hidden");
  } else {
    document.querySelector(selector).disabled = false;
    document.querySelector(selector + " .spinner").classList.add("hidden");
    document
      .querySelector(selector + " .button-text")
      .classList.remove("hidden");
  }
};

/* Show a success message when the payment is complete */
var paymentIntentSucceeded = function(clientSecret, viewSelector) {
  hideEl(viewSelector);
  showEl(".code-preview");
  stripe.retrievePaymentIntent(clientSecret).then(function(result) {
    var paymentIntent = result.paymentIntent;
    var paymentIntentJson = JSON.stringify(paymentIntent, null, 2);
    document.querySelector("pre").textContent = paymentIntentJson;
    document.querySelector(".code-preview").classList.add("expand");
  });
};
