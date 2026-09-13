FROM rabbitmq:4.2.9-management

ARG PLUGIN_VERSION=4.2.0

RUN apt-get update && apt-get install -y curl --no-install-recommends && rm -rf /var/lib/apt/lists/*

RUN curl -L -o /plugins/rabbitmq_delayed_message_exchange-${PLUGIN_VERSION}.ez \
    https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v${PLUGIN_VERSION}/rabbitmq_delayed_message_exchange-${PLUGIN_VERSION}.ez

RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
