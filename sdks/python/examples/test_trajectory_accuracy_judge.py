#!/usr/bin/env python3
"""
Test script for trajectory_accuracy_judge function.

This script demonstrates how to use the trajectory accuracy evaluation function
with a sample ReAct-style agent trajectory.
"""

import sys
import os

# Add the current directory to Python path to import our module
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from opik.evaluation.metrics.llm_judges.trajectory_accuracy import trajectory_accuracy_judge


def test_basic_example():
    """Test the trajectory accuracy judge with a basic example."""
    print("Testing trajectory_accuracy_judge with basic example...")
    print("=" * 60)
    
    example = {
        "goal": "Find the weather in Paris",
        "trajectory": [
            {
                "thought": "I need to search for weather information in Paris",
                "action": "search_weather(location='Paris')",
                "observation": "Found weather data for Paris: 22°C, sunny"
            },
            {
                "thought": "I found the weather, now summarizing",
                "action": "summarize_weather()",
                "observation": "The weather in Paris is 22°C and sunny"
            }
        ],
        "final_result": "The weather in Paris is 22°C and sunny"
    }
    
    try:
        result = trajectory_accuracy_judge(example)
        
        print("INPUT:")
        print(f"Goal: {example['goal']}")
        print(f"Number of trajectory steps: {len(example['trajectory'])}")
        print(f"Final result: {example['final_result']}")
        print()
        
        print("OUTPUT:")
        print(f"Score: {result['score']}")
        print(f"Explanation: {result['explanation']}")
        print()
        
        # Validate result format
        assert isinstance(result['score'], float), "Score should be a float"
        assert 0.0 <= result['score'] <= 1.0, f"Score {result['score']} should be between 0.0 and 1.0"
        assert isinstance(result['explanation'], str), "Explanation should be a string"
        assert len(result['explanation']) > 0, "Explanation should not be empty"
        
        print("✅ Test passed successfully!")
        return True
        
    except Exception as e:
        print(f"❌ Test failed with error: {e}")
        return False


def test_edge_cases():
    """Test the trajectory accuracy judge with edge cases."""
    print("\nTesting edge cases...")
    print("=" * 60)
    
    test_cases = [
        {
            "name": "Empty trajectory",
            "example": {
                "goal": "Do something",
                "trajectory": [],
                "final_result": "Nothing was done"
            }
        },
        {
            "name": "Missing goal",
            "example": {
                "trajectory": [
                    {
                        "thought": "I need to do something",
                        "action": "do_action()",
                        "observation": "Action completed"
                    }
                ],
                "final_result": "Task completed"
            }
        },
        {
            "name": "Incomplete trajectory step",
            "example": {
                "goal": "Find information",
                "trajectory": [
                    {
                        "thought": "I need to search",
                        # Missing action and observation
                    }
                ],
                "final_result": "Search completed"
            }
        }
    ]
    
    passed_tests = 0
    for test_case in test_cases:
        print(f"\nTesting: {test_case['name']}")
        try:
            result = trajectory_accuracy_judge(test_case['example'])
            print(f"  Score: {result['score']}")
            print(f"  Explanation: {result['explanation'][:100]}...")
            
            # Basic validation
            assert isinstance(result['score'], float)
            assert 0.0 <= result['score'] <= 1.0
            assert isinstance(result['explanation'], str)
            
            print("  ✅ Passed")
            passed_tests += 1
            
        except Exception as e:
            print(f"  ❌ Failed: {e}")
    
    print(f"\nEdge case tests: {passed_tests}/{len(test_cases)} passed")
    return passed_tests == len(test_cases)


def test_complex_trajectory():
    """Test with a more complex multi-step trajectory."""
    print("\nTesting complex trajectory...")
    print("=" * 60)
    
    example = {
        "goal": "Research and summarize the population of the top 3 largest cities in France",
        "trajectory": [
            {
                "thought": "I need to find information about the largest cities in France first",
                "action": "search(query='largest cities in France')",
                "observation": "Found that Paris, Marseille, and Lyon are the top 3 largest cities"
            },
            {
                "thought": "Now I need to get population data for Paris",
                "action": "search(query='Paris France population 2024')",
                "observation": "Paris population is approximately 2.16 million"
            },
            {
                "thought": "Next, I need population data for Marseille",
                "action": "search(query='Marseille France population 2024')",
                "observation": "Marseille population is approximately 870,000"
            },
            {
                "thought": "Finally, I need population data for Lyon",
                "action": "search(query='Lyon France population 2024')",
                "observation": "Lyon population is approximately 520,000"
            },
            {
                "thought": "Now I have all the data, I should summarize it",
                "action": "summarize(data='Paris: 2.16M, Marseille: 870K, Lyon: 520K')",
                "observation": "Summary created with population data for top 3 French cities"
            }
        ],
        "final_result": "The top 3 largest cities in France by population are: 1) Paris (2.16 million), 2) Marseille (870,000), 3) Lyon (520,000)"
    }
    
    try:
        result = trajectory_accuracy_judge(example)
        
        print("COMPLEX TRAJECTORY TEST:")
        print(f"Goal: {example['goal']}")
        print(f"Steps: {len(example['trajectory'])}")
        print(f"Score: {result['score']}")
        print(f"Explanation: {result['explanation']}")
        
        assert isinstance(result['score'], float)
        assert 0.0 <= result['score'] <= 1.0
        assert isinstance(result['explanation'], str)
        
        print("✅ Complex trajectory test passed!")
        return True
        
    except Exception as e:
        print(f"❌ Complex trajectory test failed: {e}")
        return False


if __name__ == "__main__":
    print("Trajectory Accuracy Judge Test Suite")
    print("=" * 60)
    
    # Run all tests
    tests_passed = 0
    total_tests = 3
    
    if test_basic_example():
        tests_passed += 1
    
    if test_edge_cases():
        tests_passed += 1
        
    if test_complex_trajectory():
        tests_passed += 1
    
    print("\n" + "=" * 60)
    print(f"FINAL RESULTS: {tests_passed}/{total_tests} test suites passed")
    
    if tests_passed == total_tests:
        print("🎉 All tests passed successfully!")
        sys.exit(0)
    else:
        print("⚠️  Some tests failed. Please check the output above.")
        sys.exit(1) 