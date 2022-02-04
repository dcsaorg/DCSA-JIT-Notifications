#!/usr/bin/python3

import argparse
import base64
import csv
import json
import os
import requests
import secrets
import sys
import textwrap

from dataclasses import dataclass


@dataclass
class Participant:

    name: str
    role: str
    # We support multiple smdgCodes for training clusters where multiple
    # carriers are mashed into a single instance.  But most of the time,
    # it will be "one carrier <=> one instance" and instead the json
    # file will contain the "smdg" field which holds a single value.
    smdgCodes: set[str] = frozenset()

    @property
    def is_port_member(self) -> 'bool':
        return self.role in ('ATH', 'TR')

    @property
    def is_carrier(self) -> 'bool':
        return self.role in ('CA', 'VSL', 'AG')

    @property
    def is_dcsa(self):
        return self.role == 'DCSA'

    @classmethod
    def from_map(cls, **map):
        if 'smdg' in map:
            code = map['smdg']
            del map['smdg']
            map['smdgCodes'] = frozenset([code])
        elif 'smdgCodes' in map:
            map['smdgCodes'] = frozenset(map['smdgCodes'])
        return Participant(**map)


class ClusterDefinition:

    def __init__(self, data):
        self.name = data['name']
        self.environments = data.get('environments', [])
        self.applications = data['applications']
        self.participants = {n: Participant.from_map(**x, name=n) for n, x in data['participants'].items()}
        if len(self.environments) < 1:
            if not data.get("single-environment-cluster", False):
                raise ValueError("Missing environments - if there are no environments, then "
                                 "use '\"single-environment-cluster\": true' in the cluster definition")
        if not any(p.is_port_member for p in self.participants.values()):
            raise ValueError("Please add a port member (TR/ATH) under participants")
        if not any(p.is_carrier for p in self.participants.values()):
            raise ValueError("Please add a carrier (CA/AG/VSL) under participants")

    def application_url(self, application_name, environment, participant):
        if participant not in self.participants:
            raise ValueError(f"Unknown participant {participant}")
        url = self.applications[application_name] \
            .replace("{{name}}", self.name) \
            .replace("{{participant}}", participant)
        if environment is not None:
            url = url.replace("{{environment}}", environment)
        return url

    @classmethod
    def from_file(cls, filename) -> 'ClusterDefinition':
        with open(filename) as fd:
            return ClusterDefinition(json.load(fd))


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
    response = requests.delete(base_url + "/notification-endpoints/" + endpoint_id,
                            headers=headers,
                            )
    return _unpack_response(response)


def ensure_vessel_exist(base_url, headers, vessel):
    vessel_imo_number = vessel["vesselIMONumber"]
    response = requests.get(base_url + "/unofficial/vessels",
                            params=[
                                ('vesselIMONumber', vessel_imo_number),
                                ('limit', 2),
                            ],
                            headers=headers,
                            )
    res = _unpack_response(response)
    if res:
        if len(res) > 1:
            # Should not happen any more (thanks to an UNIQUE constraint)
            raise ValueError(f"IMO is reused in {base_url}")
        assert len(res) == 1
        # vessel exists - we are happy
        return False
    response = requests.post(base_url + "/unofficial/vessels",
                            headers=headers,
                            json=vessel
                            )
    res = _unpack_response(response)
    assert res["vesselIMONumber"] == res["vesselIMONumber"]
    return True


def update_endpoint_definition(base_url, headers, endpoint_definition):
    endpoint_id = endpoint_definition["endpointID"]
    response = requests.put(base_url + "/notification-endpoints/" + endpoint_id,
                            headers=headers,
                            json=endpoint_definition,
                            )
    return _unpack_response(response)


def find_subscription_by_callback_url(base_url, headers, vessel_imo_number, callback_url):
    subscription_base_url = base_url + "/event-subscriptions/"
    response = requests.get(subscription_base_url,
                            headers=headers,
                            params=[
                                    ('callbackUrl', callback_url),
                                    ('vesselIMONumber', vessel_imo_number),
                                    # Work around for missing query parameters for now.
                                    ('limit', 100),
                                ],
                            )
    res = _unpack_response(response)
    if res:
        for r in res:
            if r["callbackUrl"] == callback_url and r["vesselIMONumber"] == vessel_imo_number:
                return r
        return None
    return None


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


def handle_subscription(subscriber_base_url, subscriber_headers, publisher_base_url, publisher_headers, receiver_reference, vessel_imo_number):
    endpoint_def = find_or_create_endpoint(subscriber_base_url, subscriber_headers, receiver_reference)
    callback_url = subscriber_base_url + "/notification-endpoints/receive/" + endpoint_def["endpointID"]
    subscription_url = endpoint_def.get("subscriptionURL")
    if subscription_url == 'TOO-LONG':
        subscription_url = None
    if subscription_url is None:
        subscription_id = endpoint_def["subscriptionID"]
        if subscription_id is not None:
            subscription_url = publisher_base_url + "/event-subscriptions/" + subscription_id
    if subscription_url == '':
        subscription_url = None
    subscription_def = None
    if subscription_url is not None:
        try:
            subscription_def = load_subscription(subscription_url, publisher_headers)
        except requests.exceptions.HTTPError as e:
            if e.response.status_code != 404:
                raise
            delete_endpoint(subscriber_base_url, subscriber_headers, endpoint_def)
            endpoint_def = find_or_create_endpoint(subscriber_base_url, subscriber_headers, receiver_reference)
            callback_url = subscriber_base_url + "/notification-endpoints/receive/" + endpoint_def["endpointID"]
    else:
        subscription_def = find_subscription_by_callback_url(publisher_base_url, publisher_headers, vessel_imo_number, callback_url)

    if subscription_def is not None:
        subscription_def["vesselIMONumber"] = vessel_imo_number
        subscription_def["callbackUrl"] = callback_url
        update_subscription(subscription_url, publisher_headers, subscription_def)
    else:
        subscription_def = {
            "callbackUrl": callback_url,
            "secret": endpoint_def["secret"],
            "vesselIMONumber": vessel_imo_number
        }
        subscription_def = create_subscription(publisher_base_url, publisher_headers, subscription_def)
        subscription_url = publisher_base_url + "/event-subscriptions/" + subscription_def["subscriptionID"]
    assert subscription_def is not None
    endpoint_def["subscriptionID"] = subscription_def["subscriptionID"]
    if len(subscription_url) < 500:
        endpoint_def["subscriptionURL"] = publisher_base_url + "/event-subscriptions/" + subscription_def["subscriptionID"]
    else:
        endpoint_def["subscriptionURL"] = "TOO-LONG"
    if endpoint_def.get("managedEndpoint") is None:
        endpoint_def["managedEndpoint"] = False
    update_endpoint_definition(subscriber_base_url, subscriber_headers, endpoint_def)
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
            subscriber_base_url = subst_environment(row["Subscriber Base URL"], subscriber_baseurl)
            publisher_base_url = subst_environment(row["Publisher Base URL"], publisher_baseurl)
            yield row["Subscriber Reference"], subscriber_base_url, publisher_base_url, vesselIMONumber


def parse_vessels(filename):
    with open(filename) as fd:
        reader = csv.DictReader(fd)
        for row in reader:
            yield {
                "vesselIMONumber": row["Vessel IMO Number"],
                "vesselName": row["Vessel Name"],
                "vesselOperatorCarrierCode": row["Vessel Operator SMDG Code"],
                "vesselOperatorCarrierCodeListProvider": "SMDG",
            }


def parse_headers(input_headers):
    headers = {}
    if input_headers is not None:
        for h in input_headers:
            k, v = h.split(':', 1)
            headers[k.strip()] = v.strip()
    return headers


def main():
    description = textwrap.dedent("""\
    Loads information about a DCSA JIT cluster and ensures that:
     1) All vessels are loaded into all listed participant instances
     2) Subscriptions are correctly set up between participants
    
    Example Usage:
    
      prepare-cluster subscriptions/hamburg/ test \\
         --header 'Authorization: Bearer eyJ...3pA'
    
    This would load subscriptions/hamburg/cluster-definition.json and
    subscriptions/hamburg/vessels.csv and load them into the test
    environment.
    
    The cluster definition file
    ===========================
    
    A sample example of a cluster-definition.json file:
    {
        "name": "singapore",
        "environments": ["test", "production"],
        "applications": {
            "ui-support": "https://{{participant}}.p6-{{name}}-{{environment}}.dcsa.org/ui-support/v1",
            "jit": "https://{{participant}}.p6-{{name}}-{{environment}}.dcsa.org/jit/v1",
            "jit-notifications": "https://{{participant}}.p6-{{name}}-{{environment}}.dcsa.org/jit-notifications/v1"
        },
        "participants": {
        "evergreen-marine": {
            "role": "CA",
            "smdg": "EMC"
        },
        "psa": {
            "role": "ATH",
        }
    }
    
    Only carrier participants need an SMDG code.  The "role" field is used
    to determine whether the participant is a carrier (CA, VSL or AG), a
    port (ATH, TR) or a spectator (DCSA).  This decides how subscriptions
    are sent up.  Carriers are only subscribed to their own vessels, where
    as ports (and the spectators) are subscribed to all relevant vessels.
    
    Note the {{X}} placeholders in the application URLs.  These are
    substituted at runtime.  The following variables are available:
    
     * {{name}} - name field from the cluster-definitions.json
     * {{participant}} - name of the participant (the key in 
       the participants dict)
     * {{environment}} - Command line parameter.
    
    The vessels.csv file
    ====================
    
    CSV file of vessels. Required headers in the CSV file:
      - Vessel IMO Number
      - Vessel Name
      - Vessel Operator SMDG Code
    """)
    parser = argparse.ArgumentParser(
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=description,
    )
    parser.add_argument("cluster_definitions_path", type=str, help="Path to the cluster definitions")
    parser.add_argument("environment", type=str, default=None, nargs='?', help="Which environment to update")
    parser.add_argument("--header", action='append', type=str, default=None, dest="headers",
                        help="Add header to all the requests")
    args = parser.parse_args()
    headers = parse_headers(args.headers)

    if os.path.isdir(args.cluster_definitions_path):
        cluster_directory = args.cluster_definitions_path
        cluster_definitions_file = os.path.join(cluster_directory, "cluster-definition.json")
    else:
        cluster_definitions_file = args.cluster_definitions_path
        cluster_directory = os.path.dirname(cluster_definitions_file)

    cluster_definition = ClusterDefinition.from_file(cluster_definitions_file)
    if args.environment is None and cluster_definition.environments:
        print(f'Missing environment for cluster {cluster_definition.name}.  Please pick one of:')
        for environment in cluster_definition.environments:
            print(f" - {environment}")
        sys.exit(1)
    if args.environment is not None and args.environment not in cluster_definition.environments:
        if cluster_definition.environments:
            print(f'The cluster {cluster_definition.name} does not have any environments.')
        else:
            print(f'Unknown environment ("{args.environment}") for cluster {cluster_definition.name}.  Please pick one of:')
            for environment in cluster_definition.environments:
                print(f" - {environment}")
        sys.exit(1)

    vessels = list(parse_vessels(os.path.join(cluster_directory, "vessels.csv")))

    for participant_name, participant in cluster_definition.participants.items():
        ui_support = cluster_definition.application_url("ui-support", args.environment, participant_name)
        jit_notifications = cluster_definition.application_url("jit-notifications", args.environment, participant_name)
        print(f"Ensuring that {participant_name} has all the vessels")
        for vessel in vessels:
            if ensure_vessel_exist(ui_support, headers, vessel):
                print(f" * Created vessel {vessel['vesselIMONumber']}")

        for publisher in cluster_definition.participants.values():
            subscriptions = compute_subscriptions(participant, publisher, vessels)
            publisher_jit = cluster_definition.application_url("jit", args.environment, publisher.name)
            print(f"Setting up subscription between {participant_name} (subscriber) and {publisher.name} (publisher)")
            # TODO: clean up old subscriptions
            if not subscriptions:
                continue
            for subscriber_reference, vessel_imo_number in subscriptions:
                # print(f"P: {publisher} -> S: {participant}, V: {vessel_imo_number}")
                handle_subscription(jit_notifications, headers, publisher_jit, headers, subscriber_reference, vessel_imo_number)


def _create_subscription(subscriber, publisher, vessel):
    reference = f"!SSM-{subscriber.name}-{publisher.name}"
    vessel_imo_number = None
    if vessel:
        vessel_imo_number = vessel['vesselIMONumber']
        reference = f"!SSM-{subscriber.name}-{publisher.name}-{vessel_imo_number}"

    return reference, vessel_imo_number


def compute_subscriptions(subscriber, publisher, vessels):
    # never subscribe to one self.
    if subscriber == publisher:
        return []

    if publisher.is_dcsa:
        return []

    if subscriber.is_carrier and publisher.is_carrier:
        return []

    if subscriber.is_dcsa:
        return [_create_subscription(subscriber, publisher, None)]

    if subscriber.is_carrier or publisher.is_carrier:
        carrier_smdg = publisher.smdgCodes or subscriber.smdgCodes
        return [_create_subscription(subscriber, publisher, v)
                for v in vessels if v["vesselOperatorCarrierCode"] in carrier_smdg]

    return [_create_subscription(subscriber, publisher, v)
            for v in vessels]


if __name__ == '__main__':
    main()
