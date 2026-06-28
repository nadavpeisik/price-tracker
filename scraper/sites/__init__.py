"""Site-specific scraper handlers, hostname-dispatched from main.py.

Each module here owns the knowledge (endpoints, selectors, JSON shapes) for one site that the
generic extraction waterfall can't handle. main.py is the only place that knows these exist.
"""
