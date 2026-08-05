import unittest

import pandas as pd

from mapping_engine import apply_workflow_mappings


class MappingEngineTest(unittest.TestCase):
    def test_direct_mapping_renames_hospital_field_to_pivot(self):
        frame = pd.DataFrame([{"patientId": "P001", "name": "Alice"}])
        mappings = [{
            "sourceName": "hospital_a",
            "mappingType": "DIRECT",
            "sourceFields": ["patientId"],
            "iolTerm": "patient_id",
        }]

        result = apply_workflow_mappings(frame, mappings)

        self.assertEqual("P001", result.loc[0, "patient_id"])
        self.assertNotIn("patientId", result.columns)

    def test_already_pivot_is_not_mapped_twice(self):
        frame = pd.DataFrame([{"patient_id": "P001"}])
        result = apply_workflow_mappings(frame, [{
            "mappingType": "DIRECT",
            "sourceFields": ["patientId"],
            "iolTerm": "patient_id",
        }], already_pivot=True)
        self.assertEqual(["patient_id"], list(result.columns))


if __name__ == "__main__":
    unittest.main()
