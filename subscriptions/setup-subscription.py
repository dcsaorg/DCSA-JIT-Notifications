#!/usr/bin/python3

import argparse
import base64
import csv
import requests
import secrets
import textwrap


def _unpack_response(response):
    with response:
        try:
            response.raise_for_status()
        except requests.exceptions.HTTPError as e:
            # Silently re-raise 404s by default
            if e.response.status_code == 404:
                raise e
            print(f"Failed - dumping debug - PATH {response.request.path_url}")
            print(response)
            print("REQUEST - HEADERS")
            print(response.request.headers)
            print("REQUEST - BODY")
            print(response.request.body)
            print("RESPONSE - HEADERS")
            print(response.headers)
            print("RESPONSE - BODY")
            print(response.content)
            print("End debug")
            raise
        if response.status_code == 204:
            return None
        return response.json()


def generate_secret():
    return base64.b64encode(secrets.token_bytes(32)).decode('ascii')


# r = find_or_create_endpoint(base_url, None, "foo")
def find_or_create_endpoint(base_url, headers, reference):
    response = requests.get(base_url + "/notification-endpoints",
                            headers=headers,
                            params=[('endpointReference', reference)],
                            )
    payload = _unpack_response(response)
    if payload and len(payload) == 1:
        return payload[0]
    payload = {
        "endpointReference": reference,
        "secret": generate_secret()
    }
    response = requests.post(base_url + "/notification-endpoints",
                             headers=headers,
                             json=payload,
                             )
    return _unpack_response(response)


def delete_endpoint(base_url, headers, endpoint_definition):
    endpoint_id = endpoint_definition["endpointID"]
    print(f"DELETE {base_url}/notification-endpoints/f{endpoint_id}")
    response = requests.delete(base_url + "/notification-endpoints/" + endpoint_id,
                            headers=headers,
                            )
    return _unpack_response(response)


def update_endpoint_definition(base_url, headers, endpoint_definition):
    endpoint_id = endpoint_definition["endpointID"]
    response = requests.put(base_url + "/notification-endpoints/" + endpoint_id,
                            headers=headers,
                            json=endpoint_definition,
                            )
    return _unpack_response(response)


def load_subscription(subscription_url, headers):
    response = requests.get(subscription_url,
                            headers=headers,
                            )
    return _unpack_response(response)


def create_subscription(base_url, headers, body):
    response = requests.post(base_url + "/event-subscriptions/",
                             headers=headers,
                             json=body,
                             )
    return _unpack_response(response)


def update_subscription(subscription_url, headers, payload):
    response = requests.put(subscription_url,
                            headers=headers,
                            json=payload
                            )
    return _unpack_response(response)


def handle_subscription(receiver_base_url, receiver_headers, subscriber_base_url, subscriber_headers, receiver_reference, vessel_imo_number):
    endpoint_def = find_or_create_endpoint(receiver_base_url, receiver_headers, receiver_reference)
    callback_url = receiver_base_url + "/notification-endpoints/receive/" + endpoint_def["endpointID"]
    subscription_url = endpoint_def.get("subscriptionURL")
    if subscription_url == 'TOO-LONG':
        subscription_url = None
    if subscription_url is None:
        subscription_id = endpoint_def["subscriptionID"]
        if subscription_id is not None:
            subscription_url = subscriber_base_url + "/event-subscriptions/" + subscription_id
    if subscription_url == '':
        subscription_url = None
    subscription_def = None
    if subscription_url is not None:
        try:
            subscription_def = load_subscription(subscription_url, subscriber_headers)
        except requests.exceptions.HTTPError as e:
            if e.response.status_code != 404:
                raise
            delete_endpoint(receiver_base_url, receiver_headers, endpoint_def)
            endpoint_def = find_or_create_endpoint(receiver_base_url, receiver_headers, receiver_reference)
            callback_url = receiver_base_url + "/notification-endpoints/receive/" + endpoint_def["endpointID"]
        else:
            subscription_def["vesselIMONumber"] = vessel_imo_number
            subscription_def["callbackUrl"] = callback_url
            update_subscription(subscription_url, subscriber_headers, subscription_def)
    if subscription_def is None:
        subscription_def = {
            "callbackUrl": callback_url,
            "secret": endpoint_def["secret"],
            "vesselIMONumber": vessel_imo_number
        }
        subscription_def = create_subscription(subscriber_base_url, subscriber_headers, subscription_def)
        subscription_url = subscriber_base_url + "/event-subscriptions/" + subscription_def["subscriptionID"]
    assert subscription_def is not None
    endpoint_def["subscriptionID"] = subscription_def["subscriptionID"]
    if len(subscription_url) < 500:
        endpoint_def["subscriptionURL"] = subscriber_base_url + "/event-subscriptions/" + subscription_def["subscriptionID"]
    else:
        endpoint_def["subscriptionURL"] = "TOO-LONG"
    if endpoint_def.get("managedEndpoint") is None:
        endpoint_def["managedEndpoint"] = False
    update_endpoint_definition(receiver_base_url, receiver_headers, endpoint_def)
    print(f'Linked {endpoint_def["endpointID"]} to subscription {endpoint_def["subscriptionID"]} (reference {receiver_reference}; callback_url {callback_url})')


def subst_environment(value, baseurl):
    if baseurl is None:
        if '{{baseurl}}' in value:
            raise ValueError("Missing --base-url parameter")
        return value
    return value.replace('{{baseurl}}', baseurl)


def parse_subscriptions(filename, subscriber_baseurl, publisher_baseurl):
    with open(filename) as fd:
        reader = csv.DictReader(fd)
        for row in reader:
            vesselIMONumber = row["Vessel IMO Number"]
            if vesselIMONumber == '':
                vesselIMONumber = None
            receiver_base_url = subst_environment(row["Subscriber Base URL"], subscriber_baseurl)
            publisher_base_url = subst_environment(row["Publisher Base URL"], publisher_baseurl)
            yield row["Subscriber Reference"], receiver_base_url, publisher_base_url, vesselIMONumber


def parse_headers(input_headers):
    headers = {}
    for h in input_headers:
        k, v = h.split(':', 1)
        headers[k.strip()] = v.strip()
    return headers


def main():
    description = textwrap.dedent("""\
    Setup subscriptions from a CSV file
    
    Example Usage:
    
      setup-subscription subscriptions/hamburg/subscriptions.csv \\
         --publisher-baseurl 'p6-singapore-production.dcsa.org/jit/v1' \\
         --subscriber-baseurl 'p6-singapore-production.dcsa.org/jit-notifications/v1' \\
         --header 'Authorization: Bearer eyJ...3pA'
    
    Required headers in the CSV file:
      - Subscriber Reference
      - Subscriber Base URL
      - Publisher Base URL
      - Vessel IMO Number
    
    The URLs in "Subscriber Base URL" and "Publisher Base URL" can contain the token
    {{baseurl}}, which will be replaced at runtime if the relevant options were
    present.
    """)
    parser = argparse.ArgumentParser(
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=description,
    )
    parser.add_argument("subscription_file", help="CSV file with subscriptions")
    parser.add_argument("--publisher-baseurl", type=str, default=None,
                        help="Value for the {{baseurl}} variable in the subscription file (if present) for publishers")
    parser.add_argument("--subscriber-baseurl", type=str, default=None,
                        help="Value for the {{baseurl}} variable in the subscription file (if present) for subscribers")
    parser.add_argument("--header", action='append', type=str, default=None, dest="headers",
                        help="Add header to all the requests")
    args = parser.parse_args()
    headers = parse_headers(args.headers)
    for subscription in parse_subscriptions(args.subscription_file, args.subscriber_baseurl, args.publisher_baseurl):
        (publisher_reference, publisher_base_url, subscriber_base_url, vessel_imo_number) = subscription
        handle_subscription(publisher_base_url, headers, subscriber_base_url, headers, publisher_reference, vessel_imo_number)


if __name__ == '__main__':
    main()
