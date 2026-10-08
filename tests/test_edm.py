from tcppeer.edm import format_port_guesses, parse_port_guesses, predict_ports


def test_predicts_stable_endpoint_dependent_mapping():
    prediction = predict_ports([41000, 41002, 41004])
    assert prediction is not None
    assert prediction.predicted_port == 41006
    assert prediction.guesses[0] == 41006


def test_does_not_classify_endpoint_independent_mapping():
    assert predict_ports([41000, 41000, 41000]) is None


def test_rejects_unstable_or_unbounded_mapping():
    assert predict_ports([41000, 41100, 41020]) is None
    assert predict_ports([41000, 41400, 41800]) is None


def test_wire_list_is_validated_and_bounded():
    value = ",".join(str(port) for port in range(1000, 1030)) + ",-1,70000,nope"
    parsed = parse_port_guesses(value)
    assert len(parsed) == 17
    assert format_port_guesses(parsed).split(",")[0] == "1000"
